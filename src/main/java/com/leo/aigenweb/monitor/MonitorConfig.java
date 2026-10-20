package com.leo.aigenweb.monitor;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 监控配置
 */
@Configuration
public class MonitorConfig {

    /**
     * 默认监听器：LangChain4j Spring Boot Starter 自动配置的模型（如工作流里的质量检查、图片规划）会自动挂上它。
     * 这些调用不属于某个应用的对话，用户和应用记为 unknown
     */
    @Bean
    public ChatModelListener defaultAiModelMonitorListener(AiModelMetricsCollector metricsCollector) {
        return new AiModelMonitorListener(metricsCollector, MonitorContext.UNKNOWN);
    }
}
