package com.leo.aigenweb.monitor;

/**
 * 监控上下文：AI 调用所属的用户和应用，作为指标的维度
 * <p>
 * 教程用 ThreadLocal 传递，但本项目的生成在后台运行，VUE 工程调用工具后的下一轮模型请求也不在原线程，
 * ThreadLocal 会取不到。这里改为创建模型实例时直接绑定（每个应用独占一个模型实例），换线程也不会丢。
 *
 * @param userId 用户 ID
 * @param appId  应用 ID
 */
public record MonitorContext(String userId, String appId) {

    /**
     * 无法确定所属应用的调用（如创建应用时的路由选择、工作流中的辅助调用）
     */
    public static final MonitorContext UNKNOWN = new MonitorContext("unknown", "unknown");

    public static MonitorContext of(Long userId, Long appId) {
        return new MonitorContext(String.valueOf(userId), String.valueOf(appId));
    }
}
