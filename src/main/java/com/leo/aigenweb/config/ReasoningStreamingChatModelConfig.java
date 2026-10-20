package com.leo.aigenweb.config;

import com.leo.aigenweb.monitor.AiModelMetricsCollector;
import com.leo.aigenweb.monitor.AiModelMonitorListener;
import com.leo.aigenweb.monitor.MonitorContext;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 推理流式对话模型（多例），用于 Vue 工程这类需要工具调用的复杂代码生成任务。
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.reasoning-streaming-chat-model")
@Data
public class ReasoningStreamingChatModelConfig {

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private boolean logRequests;

    private boolean logResponses;

    /**
     * 模型调用监控
     */
    @Resource
    private AiModelMetricsCollector aiModelMetricsCollector;

    /**
     * @param monitorContext 调用所属的用户和应用，通过 SpringContextUtil.getBean(name, type, context) 传入；
     *                       不传时为 null，指标里记为 unknown
     */
    @Bean
    @Scope("prototype")
    public StreamingChatModel reasoningStreamingChatModelPrototype(@Nullable MonitorContext monitorContext) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                // 停止生成时需要能中断流式请求
                .httpClientBuilder(StreamingHttpClientBuilders.cancellable())
                .listeners(List.of(new AiModelMonitorListener(aiModelMetricsCollector, monitorContext)))
                .build();
    }
}
