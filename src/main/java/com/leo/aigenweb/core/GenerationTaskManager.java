package com.leo.aigenweb.core;

import com.leo.aigenweb.exception.BusinessException;
import com.leo.aigenweb.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代码生成任务管理器
 * <p>
 * 生成任务在后台独立运行，不依赖浏览器的 SSE 连接：用户离开页面只会断开观看，生成仍会继续，
 * 完成后照常保存代码和对话历史。重新进入页面时可以通过 {@link #getRunning(long)} 重新接上，
 * 从头回放已生成的内容并继续接收后续内容。
 * <p>
 * 任务只保存在内存中，同一个应用同一时间只允许一个生成任务。
 */
@Slf4j
@Component
public class GenerationTaskManager {

    /**
     * 正在生成的任务：appId -> 可回放的内容流
     */
    private final Map<Long, Flux<String>> runningTasks = new ConcurrentHashMap<>();

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
        return runningTasks.get(appId);
    }

    /**
     * 在后台启动生成任务
     *
     * @param appId  应用 ID
     * @param source 生成内容流（包含保存代码、保存对话历史等处理）
     * @return 可回放的内容流，断开订阅不会中断生成
     */
    public Flux<String> start(long appId, Flux<String> source) {
        // 先于下游收到完成信号移除任务，保证前端收到 done 后立即发起的下一轮对话不会被判定为“正在生成”
        ConnectableFlux<String> task = source
                .doOnTerminate(() -> {
                    runningTasks.remove(appId);
                    log.info("应用 {} 生成任务结束", appId);
                })
                .replay();
        if (runningTasks.putIfAbsent(appId, task) != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该应用正在生成中，请等待完成后再发送");
        }
        // 立即开始生成，不等待订阅者
        task.connect();
        log.info("应用 {} 生成任务开始", appId);
        return task;
    }
}
