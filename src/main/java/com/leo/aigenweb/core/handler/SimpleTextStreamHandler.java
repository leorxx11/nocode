package com.leo.aigenweb.core.handler;

import com.leo.aigenweb.model.entity.User;
import com.leo.aigenweb.model.enums.ChatHistoryMessageTypeEnum;
import com.leo.aigenweb.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 简单文本流处理器
 * 处理 HTML 和 MULTI_FILE 类型的流式响应
 */
@Slf4j
public class SimpleTextStreamHandler {

    /**
     * 处理传统流（HTML, MULTI_FILE）
     * 直接收集完整的文本响应
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               long appId, User loginUser) {
        // 停止生成时在另一个线程读取，所以用线程安全的 StringBuffer
        StringBuffer aiResponseBuilder = new StringBuffer();
        // 完成、出错、停止三者只保存一次对话历史
        AtomicBoolean saved = new AtomicBoolean(false);
        return originFlux
                .map(chunk -> {
                    // 收集AI响应内容
                    aiResponseBuilder.append(chunk);
                    return chunk;
                })
                .doOnComplete(() -> {
                    if (!saved.compareAndSet(false, true)) {
                        return;
                    }
                    // 流式响应完成后，添加AI消息到对话历史
                    String aiResponse = aiResponseBuilder.toString();
                    chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                })
                .doOnError(error -> {
                    if (!saved.compareAndSet(false, true)) {
                        return;
                    }
                    // 如果AI回复失败，也要记录错误消息
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                })
                .doOnCancel(() -> {
                    if (!saved.compareAndSet(false, true)) {
                        return;
                    }
                    // 用户停止生成：保存已生成的部分，代码不完整所以不解析保存为文件
                    String aiResponse = GenStoppedMessage.of(aiResponseBuilder.toString());
                    chatHistoryService.addChatMessage(appId, aiResponse, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }
}
