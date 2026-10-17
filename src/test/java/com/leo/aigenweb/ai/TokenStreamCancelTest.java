package com.leo.aigenweb.ai;

import com.leo.aigenweb.config.StreamingHttpClientBuilders;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenStream.cancel() 能真正中断模型的流式 HTTP 请求：用本地假的 OpenAI 接口慢速推送 token，
 * 取消后服务端应该很快发现连接被客户端关闭，客户端也不再收到任何回调
 */
class TokenStreamCancelTest {

    interface Assistant {
        TokenStream chat(String message);
    }

    /**
     * 推理模型思考阶段的分块：只有 reasoning_content，没有正文
     */
    private static final String REASONING_CHUNK = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fake\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"think \"},\"finish_reason\":null}]}\n\n";

    private static final String CHUNK = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fake\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"tok \"},\"finish_reason\":null}]}\n\n";

    private HttpServer server;
    private final CountDownLatch clientDisconnected = new CountDownLatch(1);
    private final AtomicInteger chunksSent = new AtomicInteger();
    /**
     * 第一块响应之前的等待时间，用来模拟模型还没开始输出
     */
    private volatile long firstChunkDelayMs;
    private volatile String chunk = CHUNK;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                Thread.sleep(firstChunkDelayMs);
                // 最多推送 10 秒，远长于测试的等待时间
                for (int i = 0; i < 200; i++) {
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    chunksSent.incrementAndGet();
                    Thread.sleep(50);
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                clientDisconnected.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 每个请求一个线程，模拟多个并发的模型请求
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private TokenStream newTokenStream() {
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("test")
                .modelName("fake")
                .httpClientBuilder(StreamingHttpClientBuilders.cancellable())
                .build();
        return AiServices.builder(Assistant.class).streamingChatModel(model).build().chat("hi");
    }

    @Test
    void cancelWhileStreamingClosesConnection() throws InterruptedException {
        CountDownLatch received = new CountDownLatch(3);
        AtomicInteger tokensAfterCancel = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean terminalCallback = new AtomicBoolean();
        TokenStream tokenStream = newTokenStream();
        tokenStream.onPartialResponse(token -> {
                    if (cancelled.get()) {
                        tokensAfterCancel.incrementAndGet();
                    }
                    received.countDown();
                })
                .onCompleteResponse(response -> terminalCallback.set(true))
                .onError(error -> terminalCallback.set(true))
                .start();
        assertTrue(received.await(5, TimeUnit.SECONDS), "应该先收到几块响应");

        cancelled.set(true);
        tokenStream.cancel();

        assertTrue(clientDisconnected.await(3, TimeUnit.SECONDS), "取消后客户端应该关闭连接");
        assertTrue(chunksSent.get() < 100, "取消后不应该继续读完整个响应");
        Thread.sleep(200);
        assertEquals(0, tokensAfterCancel.get(), "取消后不应该再回调 onPartialResponse");
        assertFalse(terminalCallback.get(), "取消后不应该回调 onCompleteResponse / onError");
    }

    @Test
    void cancelBeforeFirstTokenClosesConnectionWhenFirstTokenArrives() throws InterruptedException {
        firstChunkDelayMs = 500;
        AtomicInteger tokens = new AtomicInteger();
        TokenStream tokenStream = newTokenStream();
        tokenStream.onPartialResponse(token -> tokens.incrementAndGet())
                .onError(error -> {
                })
                .start();
        Thread.sleep(200);
        tokenStream.cancel();

        assertTrue(clientDisconnected.await(3, TimeUnit.SECONDS), "第一块响应到达后应该关闭连接");
        assertEquals(0, tokens.get(), "取消后到达的响应应该被丢弃");
    }

    @Test
    void concurrentStreamsDoNotBlockEachOther() throws InterruptedException {
        // 流式读取在虚拟线程中进行，读取时如果钉住载体线程，超过 CPU 核数的并发请求会互相阻塞
        int streams = Runtime.getRuntime().availableProcessors() * 2 + 2;
        CountDownLatch allReceived = new CountDownLatch(streams);
        java.util.List<TokenStream> tokenStreams = new java.util.ArrayList<>();
        for (int i = 0; i < streams; i++) {
            AtomicInteger tokens = new AtomicInteger();
            TokenStream tokenStream = newTokenStream();
            tokenStream.onPartialResponse(token -> {
                        if (tokens.incrementAndGet() == 5) {
                            allReceived.countDown();
                        }
                    })
                    .onError(error -> {
                    })
                    .start();
            tokenStreams.add(tokenStream);
        }
        assertTrue(allReceived.await(3, TimeUnit.SECONDS), "所有并发请求都应该同时收到响应");
        tokenStreams.forEach(TokenStream::cancel);
    }

    @Test
    void cancelWhileModelIsThinkingClosesConnection() throws InterruptedException {
        chunk = REASONING_CHUNK;
        AtomicInteger tokens = new AtomicInteger();
        TokenStream tokenStream = newTokenStream();
        tokenStream.onPartialResponse(token -> tokens.incrementAndGet())
                .onError(error -> {
                })
                .start();
        // 等模型“思考”一会儿，期间没有任何正文
        Thread.sleep(500);
        assertTrue(chunksSent.get() > 0);
        tokenStream.cancel();

        assertTrue(clientDisconnected.await(3, TimeUnit.SECONDS), "思考阶段取消也应该关闭连接");
        assertEquals(0, tokens.get());
    }
}
