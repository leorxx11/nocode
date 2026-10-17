package com.leo.aigenweb.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.leo.aigenweb.annotation.AuthCheck;
import com.leo.aigenweb.common.BaseResponse;
import com.leo.aigenweb.common.DeleteRequest;
import com.leo.aigenweb.common.ResultUtils;
import com.leo.aigenweb.constant.AppConstant;
import com.leo.aigenweb.constant.UserConstant;
import com.leo.aigenweb.exception.BusinessException;
import com.leo.aigenweb.exception.ErrorCode;
import com.leo.aigenweb.exception.ThrowUtils;
import com.leo.aigenweb.model.dto.app.*;
import com.leo.aigenweb.model.entity.User;
import com.leo.aigenweb.model.vo.AppVO;
import com.leo.aigenweb.service.ProjectDownloadService;
import com.leo.aigenweb.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.leo.aigenweb.ratelimit.annotation.RateLimit;
import com.leo.aigenweb.ratelimit.enums.RateLimitType;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import com.leo.aigenweb.model.entity.App;
import com.leo.aigenweb.service.AppService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 应用 控制层。
 *
 * @author leo
 */
@RestController
@RequestMapping("/app")
public class AppController {

    /**
     * 对话提示词长度上限（输入框 1000 字 + 选中元素信息）
     */
    private static final int MAX_CHAT_MESSAGE_LENGTH = 2000;

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private ProjectDownloadService projectDownloadService;


    /**
     * 下载应用代码
     *
     * @param appId    应用 ID
     * @param request  请求
     * @param response 响应
     */
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        // 1. 基础校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        // 2. 查询应用信息
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 权限校验：只有应用创建者可以下载代码
        User loginUser = userService.getLoginUser(request);
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限下载该应用代码");
        }
        // 4. 构建应用代码目录路径（生成目录，非部署目录）
        String sourceDirName = app.getCodeGenType() + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 5. 检查代码目录是否存在
        File sourceDir = new File(sourceDirPath);
        ThrowUtils.throwIf(!sourceDir.exists() || !sourceDir.isDirectory(),
                ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        // 6. 下载文件名使用应用 ID，避免中文文件名导致前端取不到
        String downloadFileName = String.valueOf(appId);
        // 7. 调用通用下载服务
        projectDownloadService.downloadProjectAsZip(sourceDirPath, downloadFileName, response);
    }

    /**
     * 与应用进行聊天以生成代码
     *
     * @param appId   应用 ID
     * @param message 提示词
     * @param request HttpServletRequest
     * @return 生成的代码流
     */
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    // AI 对话成本最高：每个用户 60 秒内最多发起 5 次
    @RateLimit(key = "chat", limitType = RateLimitType.USER, rate = 5, rateInterval = 60,
            message = "AI 对话请求过于频繁，请稍后再试")
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        // 输入框限制 1000 字，再留出选中元素信息的余量；防止绕过前端直接提交超长内容
        ThrowUtils.throwIf(message.length() > MAX_CHAT_MESSAGE_LENGTH, ErrorCode.PARAMS_ERROR, "提示词过长");
        // 获取登录用户
        User loginUser = userService.getLoginUser(request);
        // SSE 流式返回
        Flux<String> contentFlux = appService.chatToGenCode(appId, message, loginUser);
        return toServerSentEvents(contentFlux);
    }

    /**
     * 查询应用是否正在生成代码（离开页面后生成仍在后台进行，重新进入时据此决定是否接上）
     *
     * @param appId   应用 ID
     * @param request HttpServletRequest
     * @return 是否正在生成
     */
    @GetMapping("/chat/gen/status")
    public BaseResponse<Boolean> getGenStatus(@RequestParam Long appId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.isGenerating(appId, loginUser));
    }

    /**
     * 重新接上正在进行的代码生成：先回放已生成的内容，再继续推送后续内容
     *
     * @param appId   应用 ID
     * @param request HttpServletRequest
     * @return 生成的代码流
     */
    @GetMapping(value = "/chat/gen/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> resumeGenCode(@RequestParam Long appId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return toServerSentEvents(appService.resumeGenCode(appId, loginUser));
    }

    /**
     * 把内容流转换为 SSE 事件，结束时追加 done 事件
     */
    private Flux<ServerSentEvent<String>> toServerSentEvents(Flux<String> contentFlux) {
        return contentFlux
                .map(chunk -> {
                    // 包装为 JSON（防止空格丢失）
                    Map<String, String> wrapper = Map.of("d", chunk);
                    String jsonData = JSONUtil.toJsonStr(wrapper);
                    return ServerSentEvent.<String>builder()
                            .data(jsonData)
                            .build();
                })
                // 增加事件完成响应
                .concatWith(Mono.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ));
    }

    /**
     * 部署应用
     *
     * @param appDeployRequest 部署请求
     * @param request HttpServletRequest
     * @return 部署后的应用访问地址
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取登录用户
        User loginUser = userService.getLoginUser(request);
        // 部署应用
        String appUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(appUrl);
    }


    /**
     * 创建应用
     *
     * @param appAddRequest 创建应用请求
     * @param request       HttpServletRequest
     * @return 新应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        Long appId = appService.createApp(appAddRequest, loginUser);
        return ResultUtils.success(appId);
    }

    /**
     * 根据 id 修改自己的应用（目前只支持修改应用名称）
     *
     * @param appUpdateRequest 更新应用请求
     * @param request          HttpServletRequest
     * @return 是否更新成功
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        if (appUpdateRequest == null || appUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = appUpdateRequest.getId();
        // 判断应用是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人可修改
        if (!oldApp.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        App app = new App();
        app.setId(id);
        app.setAppName(appUpdateRequest.getAppName());
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 删除自己的应用
     *
     * @param deleteRequest 删除请求
     * @param request       HttpServletRequest
     * @return 是否删除成功
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断应用是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!oldApp.getUserId().equals(loginUser.getId()) && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = appService.removeById(id);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 查看应用详情
     *
     * @param id 应用 id
     * @return 应用封装类
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类（包含创建用户信息）
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 分页查询自己的应用列表（支持根据名称查询，每页最多 20 个）
     *
     * @param appQueryRequest 查询请求
     * @param request         HttpServletRequest
     * @return 应用封装类分页
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询当前登录用户的应用
        appQueryRequest.setUserId(loginUser.getId());
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize),
                appService.getQueryWrapper(appQueryRequest));
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 分页查询精选的应用列表（支持根据名称查询，每页最多 20 个）
     *
     * @param appQueryRequest 查询请求
     * @return 应用封装类分页
     */
    @PostMapping("/good/list/page/vo")
    // 旁路缓存：只缓存前 10 页（绝大多数用户只看前几页），5 分钟后自动过期
    @Cacheable(
            value = "good_app_page",
            key = "T(com.leo.aigenweb.utils.CacheKeyUtils).generateKey(#appQueryRequest)",
            condition = "#appQueryRequest != null && #appQueryRequest.pageNum <= 10"
    )
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize),
                appService.getQueryWrapper(appQueryRequest));
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 根据 id 删除任意应用（仅管理员）
     *
     * @param deleteRequest 删除请求
     * @return 是否删除成功
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = deleteRequest.getId();
        // 判断应用是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = appService.removeById(id);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 更新任意应用（仅管理员，支持更新应用名称、应用封面、优先级）
     *
     * @param appAdminUpdateRequest 管理员更新应用请求
     * @return 是否更新成功
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        if (appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = appAdminUpdateRequest.getId();
        // 判断应用是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        App app = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, app);
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 分页查询应用列表（仅管理员，支持根据除时间外的任何字段查询，每页数量不限）
     *
     * @param appQueryRequest 查询请求
     * @return 应用封装类分页
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize),
                appService.getQueryWrapper(appQueryRequest));
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 根据 id 查看应用详情（仅管理员）
     *
     * @param id 应用 id
     * @return 应用封装类
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(appService.getAppVO(app));
    }

}
