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
 * 通用流式对话模型（多例）。
 * <p>
 * 单例的 ChatModel 在底层同步解析数据流，多个请求共用一个实例会串行执行；
 * 声明为 prototype 后，每次从容器获取都是全新实例，AI 服务之间互不阻塞。
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.streaming-chat-model")
@Data
public class StreamingChatModelConfig {

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
    public StreamingChatModel streamingChatModelPrototype(@Nullable MonitorContext monitorContext) {
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
