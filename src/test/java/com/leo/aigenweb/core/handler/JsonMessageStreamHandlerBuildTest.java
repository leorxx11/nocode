package com.leo.aigenweb.core.handler;

import cn.hutool.json.JSONUtil;
import com.leo.aigenweb.ai.model.message.AiResponseMessage;
import com.leo.aigenweb.core.builder.VueProjectBuilder;
import com.leo.aigenweb.model.entity.User;
import com.leo.aigenweb.service.ChatHistoryService;
import com.leo.aigenweb.constant.AppConstant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Vue 工程改为同步构建：流结束（下游收到 complete / done）之前，构建必须已经完成
 */
class JsonMessageStreamHandlerBuildTest {

    private final VueProjectBuilder builder = mock(VueProjectBuilder.class);
    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final JsonMessageStreamHandler handler = new JsonMessageStreamHandler();

    JsonMessageStreamHandlerBuildTest() {
        ReflectionTestUtils.setField(handler, "vueProjectBuilder", builder);
    }

    private Flux<String> aiChunks() {
        return Flux.just(JSONUtil.toJsonStr(new AiResponseMessage("hello ")), JSONUtil.toJsonStr(new AiResponseMessage("world")));
    }

    private User user() {
        User user = new User();
        user.setId(7L);
        return user;
    }

    @Test
    void buildFinishesBeforeStreamCompletes() {
        AtomicBoolean built = new AtomicBoolean();
        when(builder.buildProject(anyString())).thenAnswer(inv -> {
            Thread.sleep(200);
            built.set(true);
            return true;
        });
        AtomicBoolean builtWhenCompleted = new AtomicBoolean();
        List<String> out = handler.handle(aiChunks(), chatHistoryService, 42L, user())
                .doOnComplete(() -> builtWhenCompleted.set(built.get()))
                .collectList().block();
        assertEquals(List.of("hello ", "world"), out);
        assertTrue(builtWhenCompleted.get(), "下游收到完成信号时构建应该已经结束");
        verify(builder, times(1)).buildProject(contains("vue_project_42"));
        // 对话历史在构建之前已经保存
        verify(chatHistoryService).addChatMessage(eq(42L), eq("hello world"), anyString(), eq(7L));
    }

    @Test
    void buildExceptionDoesNotBreakTheStream() {
        when(builder.buildProject(anyString())).thenThrow(new RuntimeException("npm exploded"));
        List<String> out = handler.handle(aiChunks(), chatHistoryService, 42L, user()).collectList().block();
        assertEquals(List.of("hello ", "world"), out);
    }

    @Test
    void failedGenerationDoesNotBuild() {
        AtomicInteger calls = new AtomicInteger();
        when(builder.buildProject(anyString())).thenAnswer(inv -> calls.incrementAndGet() > 0);
        Flux<String> failing = aiChunks().concatWith(Flux.error(new IllegalStateException("llm down")));
        assertThrows(IllegalStateException.class,
                () -> handler.handle(failing, chatHistoryService, 42L, user()).collectList().block());
        assertEquals(0, calls.get(), "生成失败时不应该构建");
    }

    @Test
    void stopSavesPartialResponseWithoutBuilding() {
        Sinks.Many<String> upstream = Sinks.many().unicast().onBackpressureBuffer();
        handler.handle(upstream.asFlux(), chatHistoryService, 42L, user())
                .take(1)
                .subscribe();
        upstream.tryEmitNext(JSONUtil.toJsonStr(new AiResponseMessage("half")));
        // take(1) 收到第一块后取消上游，相当于停止生成
        verify(chatHistoryService).addChatMessage(eq(42L), eq("half" + AppConstant.GEN_STOPPED_MARK), anyString(), eq(7L));
        verify(chatHistoryService, times(1)).addChatMessage(anyLong(), anyString(), anyString(), anyLong());
        verify(builder, never()).buildProject(anyString());
    }
}
