package com.leo.aigenweb.config;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.spring.restclient.SpringRestClient;
import org.springframework.core.task.VirtualThreadTaskExecutor;

/**
 * 流式对话模型使用的 HTTP 客户端
 * <p>
 * LangChain4j 的 Spring RestClient 底层是 HttpURLConnection，平台线程阻塞在 socket 读取时不响应中断，
 * 停止生成时无法打断正在进行的模型请求。改为在虚拟线程中读取流式响应：虚拟线程阻塞在 socket 读取时被中断，
 * JDK 会关闭 socket 并抛出 SocketException，请求随之结束（见 TokenStream#cancel）。
 */
public final class StreamingHttpClientBuilders {

    private StreamingHttpClientBuilders() {
    }

    /**
     * 每个模型实例需要独立的 builder（模型构建时会在 builder 上设置超时）
     */
    public static HttpClientBuilder cancellable() {
        return SpringRestClient.builder()
                .streamingRequestExecutor(new VirtualThreadTaskExecutor("ai-streaming-"));
    }
}
