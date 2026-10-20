package com.leo.aigenweb.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leo.aigenweb.ai.guardrail.PromptSafetyInputGuardrail;
import com.leo.aigenweb.ai.tools.ToolManager;
import com.leo.aigenweb.exception.BusinessException;
import com.leo.aigenweb.exception.ErrorCode;
import com.leo.aigenweb.mapper.AppMapper;
import com.leo.aigenweb.model.entity.App;
import com.leo.aigenweb.model.enums.CodeGenTypeEnum;
import com.leo.aigenweb.monitor.MonitorContext;
import com.leo.aigenweb.service.ChatHistoryService;
import com.leo.aigenweb.utils.SpringContextUtil;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class AiCodeGeneratorServiceFactory {

    /**
     * 单次对话中连续调用工具的上限。Vue 工程每个文件对应一次写入调用，所以要留够余量，
     * 只拦截真正的死循环。
     */
    private static final int MAX_SEQUENTIAL_TOOLS_INVOCATIONS = 40;

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private ToolManager toolManager;

    @Resource
    private AppMapper appMapper;

    /**
     * AI 服务实例缓存
     * 缓存策略：
     * - 最大缓存 1000 个实例
     * - 写入后 30 分钟过期
     * - 访问后 10 分钟过期
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.debug("AI 服务实例被移除，缓存键: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 根据 appId 获取服务（带缓存）,兼容老方法
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
    }

    /**
     * 根据 appId 获取服务（带缓存）
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenTypeEnum) {

        String cacheKey = buildCacheKey(appId, codeGenTypeEnum);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenTypeEnum));
    }

    /**
     * 移除应用的 AI 服务缓存，下次对话时重新创建，并从数据库对话历史重新加载对话记忆
     */
    public void invalidateAiCodeGeneratorService(long appId) {
        for (CodeGenTypeEnum codeGenTypeEnum : CodeGenTypeEnum.values()) {
            serviceCache.invalidate(buildCacheKey(appId, codeGenTypeEnum));
        }
    }

    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenTypeEnum){
        return appId + "_" + codeGenTypeEnum;
    }

    /**
     * 创建新的 AI 服务实例
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenTypeEnum) {
        log.info("为 appId: {} 创建新的 AI 服务实例", appId);
        // 根据 appId 构建独立的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(20)
                .build();
        // 从数据库记载历史对话到记忆中
        chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, 20);
        // 模型实例归这个应用独占，创建时绑定监控维度（只有应用创建者能对话，用户即创建者）
        App app = appMapper.selectOneById(appId);
        MonitorContext monitorContext = app == null ? MonitorContext.UNKNOWN : MonitorContext.of(app.getUserId(), appId);

        return switch (codeGenTypeEnum) {
            case VUE_PROJECT -> {
                // 多例模型：每个 AI 服务独占一个模型实例，避免并发请求互相阻塞
                StreamingChatModel reasoningStreamingChatModel = SpringContextUtil
                        .getBean("reasoningStreamingChatModelPrototype", StreamingChatModel.class, monitorContext);
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .streamingChatModel(reasoningStreamingChatModel)
                        .chatMemoryProvider(memoryId -> chatMemory)
                        .tools(toolManager.getAllTools())
                        // 限制连续调用工具的次数，防止模型陷入工具调用死循环
                        .maxSequentialToolsInvocations(MAX_SEQUENTIAL_TOOLS_INVOCATIONS)
                        .hallucinatedToolNameStrategy(toolExecutionRequest ->
                                ToolExecutionResultMessage.from(toolExecutionRequest,
                                        "Error: there is no tool called "
                                                + toolExecutionRequest.name()))
                        .inputGuardrails(new PromptSafetyInputGuardrail())
                        .build();
            }
            case HTML, MULTI_FILE -> {
                StreamingChatModel streamingChatModel = SpringContextUtil
                        .getBean("streamingChatModelPrototype", StreamingChatModel.class, monitorContext);
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .chatModel(chatModel)
                        .streamingChatModel(streamingChatModel)
                        .chatMemory(chatMemory)
                        .inputGuardrails(new PromptSafetyInputGuardrail())
                        .build();
            }
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,"不支持的代码生成类型"
                    +  codeGenTypeEnum.getValue());
        };
    }


    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorService(0L);
    }
}
