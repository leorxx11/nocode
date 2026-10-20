package com.leo.aigenweb.config;

import com.leo.aigenweb.monitor.AiModelMetricsCollector;
import com.leo.aigenweb.monitor.AiModelMonitorListener;
import com.leo.aigenweb.monitor.MonitorContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 智能路由专用对话模型（多例）。路由只做简单分类，单独配置便宜、输出短的模型即可。
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.routing-chat-model")
@Data
public class RoutingAiModelConfig {

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
    public ChatModel routingChatModelPrototype(@Nullable MonitorContext monitorContext) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .listeners(List.of(new AiModelMonitorListener(aiModelMetricsCollector, monitorContext)))
                .build();
    }
}
