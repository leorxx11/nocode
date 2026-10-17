package com.leo.aigenweb.core;

import com.leo.aigenweb.constant.AppConstant;
import com.leo.aigenweb.exception.BusinessException;
import com.leo.aigenweb.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 代码生成任务管理器
 * <p>
 * 生成任务在后台独立运行，不依赖浏览器的 SSE 连接：用户离开页面只会断开观看，生成仍会继续，
 * 完成后照常保存代码和对话历史。重新进入页面时可以通过 {@link #getRunning(long)} 重新接上，
 * 从头回放已生成的内容并继续接收后续内容。
 * <p>
 * 用户可以通过 {@link #stop(long)} 停止生成：取消上游（中断模型请求、保存已生成部分的对话历史），
 * 所有正在观看的连接收到停止提示后正常结束。
 * <p>
 * 任务只保存在内存中，同一个应用同一时间只允许一个生成任务。
 */
@Slf4j
@Component
public class GenerationTaskManager {

    /**
     * 正在生成的任务：appId -> 任务
     */
    private final Map<Long, GenerationTask> runningTasks = new ConcurrentHashMap<>();

    /**
     * 生成任务
     *
     * @param flux       可回放的内容流
     * @param stopSignal 停止信号，发出后上游被取消、内容流正常结束
     * @param stopped    是否被用户停止
     */
    private record GenerationTask(Flux<String> flux, Sinks.One<Boolean> stopSignal, AtomicBoolean stopped) {
    }

    /**
     * 应用是否正在生成
     */
    public boolean isRunning(long appId) {
        return runningTasks.containsKey(appId);
    }

    /**
     * 获取正在生成的任务，没有则返回 null。订阅后会先回放已生成的全部内容
     */
    public Flux<String> getRunning(long appId) {
        GenerationTask task = runningTasks.get(appId);
        return task == null ? null : task.flux();
    }

    /**
     * 在后台启动生成任务
     *
     * @param appId  应用 ID
     * @param source 生成内容流（包含保存代码、保存对话历史等处理）
     * @return 可回放的内容流，断开订阅不会中断生成
     */
    public Flux<String> start(long appId, Flux<String> source) {
        Sinks.One<Boolean> stopSignal = Sinks.one();
        AtomicBoolean stopped = new AtomicBoolean(false);
        // 停止时 takeUntilOther 取消上游，同时让内容流正常结束，正在观看的连接都能收到停止提示和结束事件
        ConnectableFlux<String> flux = source
                .takeUntilOther(stopSignal.asMono())
                .concatWith(Mono.defer(() -> stopped.get() ? Mono.just(AppConstant.GEN_STOPPED_MARK) : Mono.empty()))
                // 先于下游收到完成信号移除任务，保证前端收到 done 后立即发起的下一轮对话不会被判定为“正在生成”
                .doOnTerminate(() -> {
                    runningTasks.remove(appId);
                    log.info("应用 {} 生成任务结束", appId);
                })
                .replay();
        if (runningTasks.putIfAbsent(appId, new GenerationTask(flux, stopSignal, stopped)) != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该应用正在生成中，请等待完成后再发送");
        }
        // 立即开始生成，不等待订阅者
        flux.connect();
        log.info("应用 {} 生成任务开始", appId);
        return flux;
    }

    /**
     * 停止生成
     * <p>
     * 同步完成：返回时上游已取消、已生成部分的对话历史已保存、任务已移除，可以立即发起下一轮对话
     *
     * @param appId 应用 ID
     * @return 是否停止了正在进行的生成（没有正在生成的任务时返回 false）
     */
    public boolean stop(long appId) {
        GenerationTask task = runningTasks.get(appId);
        if (task == null) {
            return false;
        }
        // 已经在停止或刚好生成结束，不重复处理
        if (!task.stopped().compareAndSet(false, true)) {
            return false;
        }
        boolean stopped = task.stopSignal().tryEmitValue(true).isSuccess();
        // 兜底：正常情况下停止信号已经同步触发 doOnTerminate 移除了任务
        runningTasks.remove(appId, task);
        log.info("应用 {} 生成任务已停止", appId);
        return stopped;
    }
}
