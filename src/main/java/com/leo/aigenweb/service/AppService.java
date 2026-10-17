package com.leo.aigenweb.service;

import com.leo.aigenweb.model.dto.app.AppAddRequest;
import com.leo.aigenweb.model.dto.app.AppQueryRequest;
import com.leo.aigenweb.model.entity.User;
import com.leo.aigenweb.model.vo.AppVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.leo.aigenweb.model.entity.App;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author leo
 */
public interface AppService extends IService<App> {

    /**
     * 获取应用封装类（包含创建用户信息）
     *
     * @param app 应用
     * @return 应用封装类
     */
    AppVO getAppVO(App app);

    /**
     * 获取应用封装类列表（包含创建用户信息）
     *
     * @param appList 应用列表
     * @return 应用封装类列表
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 分页查询请求 -> 数据库查询条件
     *
     * @param appQueryRequest 查询请求
     * @return 查询条件
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 与 AI 聊天生成代码
     *
     * @param appId 应用 ID
     * @param message 提示词
     * @param loginUser 当前登录用户
     * @return 生成的代码流
     */
    Flux<String> chatToGenCode(Long appId, String message, User loginUser);

    /**
     * 应用是否正在生成代码（仅应用创建者可查询）
     *
     * @param appId 应用 ID
     * @param loginUser 当前登录用户
     * @return 是否正在生成
     */
    boolean isGenerating(Long appId, User loginUser);

    /**
     * 重新接上正在进行的生成：先回放已生成的内容，再继续接收后续内容；没有正在生成的任务时直接结束
     *
     * @param appId 应用 ID
     * @param loginUser 当前登录用户
     * @return 生成的代码流
     */
    Flux<String> resumeGenCode(Long appId, User loginUser);

    /**
     * 部署应用
     *
     * @param appId 应用 ID
     * @param loginUser 当前登录用户
     * @return 部署地址
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 创建应用，并由 AI 智能选择代码生成类型
     *
     * @param appAddRequest 创建请求
     * @param loginUser     当前登录用户
     * @return 应用 id
     */
    Long createApp(AppAddRequest appAddRequest, User loginUser);

    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用 ID
     * @param appUrl 应用访问 URL
     */
    void generateAppScreenshotAsync(Long appId, String appUrl);
}
