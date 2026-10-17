package com.leo.aigenweb.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.leo.aigenweb.ai.AiCodeGenTypeRoutingService;
import com.leo.aigenweb.ai.AiCodeGeneratorServiceFactory;
import com.leo.aigenweb.ai.AiCodeGenTypeRoutingServiceFactory;
import com.leo.aigenweb.constant.AppConstant;
import com.leo.aigenweb.core.AiCodeGeneratorFacade;
import com.leo.aigenweb.core.GenerationTaskManager;
import com.leo.aigenweb.core.builder.VueProjectBuilder;
import com.leo.aigenweb.core.handler.StreamHandlerExecutor;
import com.leo.aigenweb.exception.BusinessException;
import com.leo.aigenweb.exception.ErrorCode;
import com.leo.aigenweb.exception.ThrowUtils;
import com.leo.aigenweb.model.dto.app.AppAddRequest;
import com.leo.aigenweb.model.dto.app.AppQueryRequest;
import com.leo.aigenweb.model.entity.User;
import com.leo.aigenweb.model.enums.ChatHistoryMessageTypeEnum;
import com.leo.aigenweb.model.enums.CodeGenTypeEnum;
import com.leo.aigenweb.model.vo.AppVO;
import com.leo.aigenweb.model.vo.UserVO;
import com.leo.aigenweb.service.ChatHistoryService;
import com.leo.aigenweb.service.ScreenshotService;
import com.leo.aigenweb.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.leo.aigenweb.model.entity.App;
import com.leo.aigenweb.mapper.AppMapper;
import com.leo.aigenweb.service.AppService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author leo
 */
@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;

    @Resource
    private ScreenshotService screenshotService;

    /**
     * 应用部署域名，线上通过 code.deploy-host 配置（如 https://example.com/dist）
     */
    @Value("${code.deploy-host:http://localhost:8080}")
    private String deployHost;

    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        // 截图较耗时，使用虚拟线程异步执行，避免阻塞部署流程
        Thread.startVirtualThread(() -> {
            try {
                String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
                App updateApp = new App();
                updateApp.setId(appId);
                updateApp.setCover(screenshotUrl);
                boolean updated = this.updateById(updateApp);
                ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
            } catch (Exception e) {
                // 封面失败不影响应用本身，前端会降级显示默认封面
                log.error("生成应用封面失败，appId: {}, url: {}", appId, appUrl, e);
            }
        });
    }

    @Override
    public Long createApp(AppAddRequest appAddRequest, User loginUser) {
        // 参数校验
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        // 构造入库对象
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称暂时为 initPrompt 前 12 位
        app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        // 使用 AI 智能选择代码生成类型（多例模型，支持并发）
        AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
        CodeGenTypeEnum selectedCodeGenType = routingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectedCodeGenType.getValue());
        // 插入数据库
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        log.info("应用创建成功，ID: {}, 类型: {}", app.getId(), selectedCodeGenType.getValue());
        return app.getId();
    }

    @Resource
    private UserService userService;
    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;
    @Resource
    private ChatHistoryService chatHistoryService;
    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;
    @Resource
    private VueProjectBuilder vueProjectBuilder;
    @Resource
    private GenerationTaskManager generationTaskManager;
    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
        // 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        // 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.PARAMS_ERROR, "应用不存在");

        // 权限校验，仅本人修改自己的应用
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }

        // 获取应用生成类型
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }

        // 同一个应用同一时间只能有一个生成任务
        ThrowUtils.throwIf(generationTaskManager.isRunning(appId), ErrorCode.OPERATION_ERROR, "该应用正在生成中，请等待完成后再发送");

        // 通过校验后，先保存用户消息到对话历史
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());

        // 调用 AI 生成代码
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
        // 收集 AI 响应内容，在流式返回完成后保存到对话历史
        Flux<String> handledStream = streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);
        // 在后台运行，浏览器断开连接（比如离开页面）不会中断生成
        return generationTaskManager.start(appId, handledStream);
    }

    @Override
    public boolean isGenerating(Long appId, User loginUser) {
        checkAppOwner(appId, loginUser);
        return generationTaskManager.isRunning(appId);
    }

    @Override
    public Flux<String> resumeGenCode(Long appId, User loginUser) {
        checkAppOwner(appId, loginUser);
        Flux<String> running = generationTaskManager.getRunning(appId);
        // 没有正在生成的任务时直接结束，前端以已保存的对话历史为准
        return running == null ? Flux.empty() : running;
    }

    @Override
    public boolean stopGenCode(Long appId, User loginUser) {
        checkAppOwner(appId, loginUser);
        boolean stopped = generationTaskManager.stop(appId);
        if (stopped) {
            // 被停止的回复没有写入 AI 对话记忆，移除缓存的 AI 服务，下次对话时从对话历史重新加载记忆
            aiCodeGeneratorServiceFactory.invalidateAiCodeGeneratorService(appId);
        }
        return stopped;
    }

    /**
     * 校验应用存在且属于当前用户
     */
    private void checkAppOwner(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
    }

    /**
     * 删除应用时，关联删除该应用的对话历史
     *
     * @param id 应用 ID
     * @return 是否删除成功
     */
    @Override
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        long appId = Long.parseLong(id.toString());
        if (appId <= 0) {
            return false;
        }
        // 先删除关联的对话历史，删除失败仅记录日志，不阻塞应用删除
        try {
            chatHistoryService.deleteByAppId(appId);
        } catch (Exception e) {
            log.error("删除应用关联对话历史失败：{}", e.getMessage());
        }
        // 再删除应用
        return super.removeById(id);
    }

    @Override
    public String deployApp(Long appId, User loginUser) {
        // 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 查询数据库中是否存在应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 权限校验，仅本人能够部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        // 查询是否已有deployKey
        // 没有则生成deployKey，6位（字母 + 数字）
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }

        // 获取代码生成类型、获取原始代码路径

        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;

        // 检查路径是否存在
        File sourceDir = new File(sourceDirPath);
        ThrowUtils.throwIf(!sourceDir.exists() || !sourceDir.isDirectory(), ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");

        // Vue 项目特殊处理：执行构建
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            // Vue 项目需要构建
            boolean buildSuccess = vueProjectBuilder.buildProject(sourceDirPath);
            ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请检查代码和依赖");
            // 检查 dist 目录是否存在
            File distDir = new File(sourceDirPath, "dist");
            ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            // 将 dist 目录作为部署源
            sourceDir = distDir;
            log.info("Vue 项目构建成功，将部署 dist 目录: {}", distDir.getAbsolutePath());
        }
        // 复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try{
            FileUtil.copyContent(sourceDir, new File(deployDirPath),true);
        }catch (Exception e){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署应用失败，复制文件异常：" + e.getMessage());
        }

        // 更新数据库
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");

        // 构建应用访问 URL
        String appDeployUrl = String.format("%s/%s/", deployHost, deployKey);
        // 异步生成截图并更新应用封面
        generateAppScreenshotAsync(appId, appDeployUrl);
        return appDeployUrl;
    }

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtils.copyProperties(app, appVO);
        // 关联查询创建用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            appVO.setUser(userService.getUserVO(user));
        }
        return appVO;
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量查询创建用户信息，避免逐条查询
        Set<Long> userIdSet = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream()
                .map(app -> {
                    AppVO appVO = new AppVO();
                    BeanUtils.copyProperties(app, appVO);
                    appVO.setUser(userVOMap.get(app.getUserId()));
                    return appVO;
                })
                .collect(Collectors.toList());
    }

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }

}
