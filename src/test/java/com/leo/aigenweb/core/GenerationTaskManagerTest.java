package com.leo.aigenweb.core;

import com.leo.aigenweb.constant.AppConstant;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 后台生成任务的停止：取消上游、所有观看者收到停止提示后正常结束、任务立即移除
 */
class GenerationTaskManagerTest {

    private final GenerationTaskManager manager = new GenerationTaskManager();

    @Test
    void stopCancelsUpstreamAndCompletesWatchers() {
        Sinks.Many<String> upstream = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<String> flux = manager.start(1L, upstream.asFlux().doOnCancel(() -> cancelled.set(true)));
        upstream.tryEmitNext("hello ");
        upstream.tryEmitNext("world");
        assertTrue(manager.isRunning(1L));

        assertTrue(manager.stop(1L));

        assertTrue(cancelled.get(), "停止时应取消上游");
        assertFalse(manager.isRunning(1L), "停止后任务应立即移除");
        // 停止后重新接上（回放）的观看者也能拿到完整内容并正常结束
        List<String> replayed = flux.collectList().block(Duration.ofSeconds(1));
        assertEquals(List.of("hello ", "world", AppConstant.GEN_STOPPED_MARK), replayed);
    }

    @Test
    void stopWhenNotRunningReturnsFalse() {
        assertFalse(manager.stop(2L));
        manager.start(2L, Flux.just("done")).blockLast(Duration.ofSeconds(1));
        assertFalse(manager.stop(2L), "已经结束的任务不能再停止");
    }

    @Test
    void canStartAgainRightAfterStop() {
        manager.start(3L, Flux.never());
        assertTrue(manager.stop(3L));
        List<String> out = manager.start(3L, Flux.just("next")).collectList().block(Duration.ofSeconds(1));
        assertEquals(List.of("next"), out);
    }

    @Test
    void naturalCompletionHasNoStopMark() {
        List<String> out = manager.start(4L, Flux.just("a", "b")).collectList().block(Duration.ofSeconds(1));
        assertEquals(List.of("a", "b"), out);
        assertFalse(manager.isRunning(4L));
    }
}
