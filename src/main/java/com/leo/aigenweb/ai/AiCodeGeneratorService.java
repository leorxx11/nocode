package com.leo.aigenweb.ai;

import com.leo.aigenweb.ai.model.HtmlCodeResult;
import com.leo.aigenweb.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface AiCodeGeneratorService {

    /**
     * 生成 HTML 代码
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(String userMessage);

    /**
     * 生成多文件代码
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(String userMessage);


    /**
     * 生成 HTML 代码流
     * <p>
     * 流式方法都返回 TokenStream 而不是 Flux：langchain4j-reactor 转换出的 Flux 被取消时不会中断模型请求，
     * 停止生成需要调用 {@link TokenStream#cancel()}
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果流
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    TokenStream generateHtmlCodeStream(String userMessage);

    /**
     * 生成多文件代码流
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果流
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    TokenStream generateMultiFileCodeStream(String userMessage);

    /**
     * 生成 VUE 项目代码（流式）
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果流
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectCodeStream(@MemoryId long appId, @UserMessage String userMessage);

}

