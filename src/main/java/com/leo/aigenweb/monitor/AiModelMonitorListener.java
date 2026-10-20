package com.leo.aigenweb.monitor;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * AI 模型调用监听器：每次请求模型时记录调用次数、响应时间、Token 消耗和错误
 * <p>
 * 每个模型实例持有自己的监听器，创建时绑定所属的用户和应用（见 {@link MonitorContext}）。
 * 请求和响应回调可能不在同一个线程，开始时间通过本次调用的 attributes 传递。
 */
@Slf4j
public class AiModelMonitorListener implements ChatModelListener {

    private static final String REQUEST_START_TIME_KEY = "monitor_request_start_time";

    private final AiModelMetricsCollector metricsCollector;

    private final MonitorContext context;

    public AiModelMonitorListener(AiModelMetricsCollector metricsCollector, MonitorContext context) {
        this.metricsCollector = metricsCollector;
        this.context = context == null ? MonitorContext.UNKNOWN : context;
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        requestContext.attributes().put(REQUEST_START_TIME_KEY, Instant.now());
        metricsCollector.recordRequest(context, modelName(requestContext.chatRequest().modelName()), "started");
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        // 以请求时的模型名为准，与 started 的统计口径一致
        String modelName = modelName(responseContext.chatRequest().modelName());
        metricsCollector.recordRequest(context, modelName, "success");
        recordResponseTime(responseContext.attributes(), modelName);
        TokenUsage tokenUsage = responseContext.chatResponse().metadata().tokenUsage();
        if (tokenUsage != null) {
            recordTokens(modelName, "input", tokenUsage.inputTokenCount());
            recordTokens(modelName, "output", tokenUsage.outputTokenCount());
            recordTokens(modelName, "total", tokenUsage.totalTokenCount());
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        String modelName = modelName(errorContext.chatRequest().modelName());
        metricsCollector.recordRequest(context, modelName, "error");
        metricsCollector.recordError(context, modelName, errorContext.error().getClass().getSimpleName());
        // 失败的请求也记录耗时，便于分析错误请求的特征
        recordResponseTime(errorContext.attributes(), modelName);
    }

    private void recordResponseTime(Map<Object, Object> attributes, String modelName) {
        if (attributes.get(REQUEST_START_TIME_KEY) instanceof Instant startTime) {
            metricsCollector.recordResponseTime(context, modelName, Duration.between(startTime, Instant.now()));
        }
    }

    private void recordTokens(String modelName, String tokenType, Integer count) {
        if (count != null) {
            metricsCollector.recordTokenUsage(context, modelName, tokenType, count);
        }
    }

    private static String modelName(String modelName) {
        return modelName == null ? "unknown" : modelName;
    }
}
