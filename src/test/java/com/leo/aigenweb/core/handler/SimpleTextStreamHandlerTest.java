package com.leo.aigenweb.core.handler;

import com.leo.aigenweb.constant.AppConstant;
import com.leo.aigenweb.model.entity.User;
import com.leo.aigenweb.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * HTML / MULTI_FILE 流处理：完成、出错、停止各保存一次对话历史
 */
class SimpleTextStreamHandlerTest {

    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final SimpleTextStreamHandler handler = new SimpleTextStreamHandler();

    private User user() {
        User user = new User();
        user.setId(7L);
        return user;
    }

    @Test
    void completeSavesFullResponse() {
        List<String> out = handler.handle(Flux.just("<html>", "</html>"), chatHistoryService, 42L, user())
                .collectList().block();
        assertEquals(List.of("<html>", "</html>"), out);
        verify(chatHistoryService, times(1)).addChatMessage(eq(42L), eq("<html></html>"), anyString(), eq(7L));
    }

    @Test
    void stopSavesPartialResponseWithStopMark() {
        Sinks.Many<String> upstream = Sinks.many().unicast().onBackpressureBuffer();
        handler.handle(upstream.asFlux(), chatHistoryService, 42L, user())
                .take(2)
                .subscribe();
        upstream.tryEmitNext("<html>");
        upstream.tryEmitNext("<body>");
        // 停止后上游再结束，不应再保存一次
        upstream.tryEmitComplete();
        verify(chatHistoryService).addChatMessage(eq(42L), eq("<html><body>" + AppConstant.GEN_STOPPED_MARK), anyString(), eq(7L));
        verify(chatHistoryService, times(1)).addChatMessage(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void stopInsideCodeBlockClosesTheFence() {
        Sinks.Many<String> upstream = Sinks.many().unicast().onBackpressureBuffer();
        handler.handle(upstream.asFlux(), chatHistoryService, 42L, user())
                .take(2)
                .subscribe();
        upstream.tryEmitNext("说明\n```html\n");
        upstream.tryEmitNext("<div>");
        verify(chatHistoryService).addChatMessage(eq(42L), eq("说明\n```html\n<div>\n```" + AppConstant.GEN_STOPPED_MARK), anyString(), eq(7L));
    }
}
