package com.leo.aigenweb.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * AI 模型指标收集器：按用户、应用、模型等维度记录最细粒度的数据，聚合分析交给 Prometheus 查询
 */
@Component
public class AiModelMetricsCollector {

    /**
     * 响应时间的直方图分桶（秒），用于在 Grafana 中计算 P50/P95 等百分位数；
     * 代码生成单次可能持续数分钟，所以分桶覆盖到 10 分钟
     */
    private static final Duration[] RESPONSE_TIME_BUCKETS = {
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10),
            Duration.ofSeconds(20), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120),
            Duration.ofSeconds(180), Duration.ofSeconds(300), Duration.ofSeconds(600)
    };

    @Resource
    private MeterRegistry meterRegistry;

    // 缓存已创建的指标，相同维度组合复用同一个对象，避免每次都重新注册
    private final ConcurrentMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> tokenCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> responseTimers = new ConcurrentHashMap<>();

    /**
     * 记录请求次数
     *
     * @param status started / success / error
     */
    public void recordRequest(MonitorContext context, String modelName, String status) {
        String key = String.join("_", context.userId(), context.appId(), modelName, status);
        requestCounters.computeIfAbsent(key, k -> Counter.builder("ai_model_requests_total")
                        .description("AI 模型请求次数")
                        .tag("user_id", context.userId())
                        .tag("app_id", context.appId())
                        .tag("model_name", modelName)
                        .tag("status", status)
                        .register(meterRegistry))
                .increment();
    }

    /**
     * 记录错误。按异常类型统计，原始错误信息千变万化，作为标签会让时间序列无限增长
     */
    public void recordError(MonitorContext context, String modelName, String errorType) {
        String key = String.join("_", context.userId(), context.appId(), modelName, errorType);
        errorCounters.computeIfAbsent(key, k -> Counter.builder("ai_model_errors_total")
                        .description("AI 模型错误次数")
                        .tag("user_id", context.userId())
                        .tag("app_id", context.appId())
                        .tag("model_name", modelName)
                        .tag("error_type", errorType)
                        .register(meterRegistry))
                .increment();
    }

    /**
     * 记录 Token 消耗
     *
     * @param tokenType input / output / total
     */
    public void recordTokenUsage(MonitorContext context, String modelName, String tokenType, long tokenCount) {
        String key = String.join("_", context.userId(), context.appId(), modelName, tokenType);
        tokenCounters.computeIfAbsent(key, k -> Counter.builder("ai_model_tokens_total")
                        .description("AI 模型 Token 消耗")
                        .tag("user_id", context.userId())
                        .tag("app_id", context.appId())
                        .tag("model_name", modelName)
                        .tag("token_type", tokenType)
                        .register(meterRegistry))
                .increment(tokenCount);
    }

    /**
     * 记录响应时间
     */
    public void recordResponseTime(MonitorContext context, String modelName, Duration duration) {
        String key = String.join("_", context.userId(), context.appId(), modelName);
        responseTimers.computeIfAbsent(key, k -> Timer.builder("ai_model_response_duration_seconds")
                        .description("AI 模型响应时间")
                        .tag("user_id", context.userId())
                        .tag("app_id", context.appId())
                        .tag("model_name", modelName)
                        .serviceLevelObjectives(RESPONSE_TIME_BUCKETS)
                        .register(meterRegistry))
                .record(duration);
    }
}
