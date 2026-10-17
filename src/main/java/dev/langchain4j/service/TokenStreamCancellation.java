package dev.langchain4j.service;

/**
 * 本项目扩展：{@link TokenStream} 的取消控制（LangChain4j 1.1.0 不支持取消流式请求）
 * <p>
 * 模型响应在 HTTP 客户端的读取线程里逐块回调，而底层 SSE 解析会吞掉回调抛出的异常，抛异常停不下读取。
 * 这里改为中断读取线程：流式响应在虚拟线程中读取（见 StreamingHttpClientBuilders），虚拟线程阻塞在 socket 读取时被中断，
 * JDK 会关闭 socket 并抛出异常，请求随之结束。
 * <ul>
 *     <li>读取模型响应期间取消：直接中断读取线程；还没收到第一个分块时，在第一个分块到达时中断（推理模型的思考内容也算）</li>
 *     <li>执行工具期间取消：不中断，避免文件写到一半；当前这批工具执行完后不再发起下一轮模型调用</li>
 * </ul>
 */
class TokenStreamCancellation {

    private volatile boolean cancelled;

    /**
     * 正在读取模型响应的线程，读取结束（准备执行工具或已结束）后为 null，保证不会中断无关的工作
     */
    private Thread streamingThread;

    /**
     * 取消生成
     */
    synchronized void cancel() {
        cancelled = true;
        if (streamingThread != null) {
            streamingThread.interrupt();
        }
    }

    boolean isCancelled() {
        return cancelled;
    }

    /**
     * 收到一块模型响应时调用：记录读取线程；已取消则中断当前线程，让后续的阻塞读取抛错结束请求
     *
     * @return 是否已取消（已取消时应丢弃这块响应）
     */
    synchronized boolean onStreaming() {
        if (cancelled) {
            Thread.currentThread().interrupt();
            return true;
        }
        streamingThread = Thread.currentThread();
        return false;
    }

    /**
     * 一次模型响应结束（正常完成或出错）时调用：之后不再中断该线程，并清除取消时可能残留的中断标记
     *
     * @return 是否已取消
     */
    synchronized boolean onResponseEnd() {
        streamingThread = null;
        if (cancelled) {
            Thread.interrupted();
        }
        return cancelled;
    }
}
