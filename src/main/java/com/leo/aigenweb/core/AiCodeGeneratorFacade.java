package com.leo.aigenweb.core;

import cn.hutool.json.JSONUtil;
import com.leo.aigenweb.ai.AiCodeGeneratorService;
import com.leo.aigenweb.ai.AiCodeGeneratorServiceFactory;
import com.leo.aigenweb.ai.model.HtmlCodeResult;
import com.leo.aigenweb.ai.model.MultiFileCodeResult;
import com.leo.aigenweb.ai.model.message.AiResponseMessage;
import com.leo.aigenweb.ai.model.message.ToolExecutedMessage;
import com.leo.aigenweb.ai.model.message.ToolRequestMessage;
import com.leo.aigenweb.core.parser.CodeParserExecutor;
import com.leo.aigenweb.core.saver.CodeFileSaverExecutor;
import com.leo.aigenweb.exception.BusinessException;
import com.leo.aigenweb.exception.ErrorCode;
import com.leo.aigenweb.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;

/**
 * AI 代码生成外观类，组合生成和保存功能
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 代码流
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId,codeGenTypeEnum);

        return switch (codeGenTypeEnum) {
            case HTML -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                yield processCodeStream(processTextTokenStream(tokenStream), CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                yield processCodeStream(processTextTokenStream(tokenStream), CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processTokenStream(tokenStream);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 将 TokenStream 转换为纯文本的 Flux<String>（HTML、MULTI_FILE）
     *
     * @param tokenStream TokenStream 对象
     * @return Flux<String> 流式响应，取消订阅会中断模型请求
     */
    private Flux<String> processTextTokenStream(TokenStream tokenStream) {
        return Flux.create(sink -> {
            // 停止生成时下游取消订阅，同时中断模型请求
            sink.onCancel(tokenStream::cancel);
            tokenStream.onPartialResponse(sink::next)
                    .onCompleteResponse(response -> sink.complete())
                    .onError(sink::error)
                    .start();
        });
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStream TokenStream 对象
     * @return Flux<String> 流式响应，取消订阅会中断模型请求
     */
    private Flux<String> processTokenStream(TokenStream tokenStream) {
        return Flux.create(sink -> {
            // 停止生成时下游取消订阅，同时中断模型请求
            sink.onCancel(tokenStream::cancel);
            tokenStream.onPartialResponse((String partialResponse) -> {
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        error.printStackTrace();
                        sink.error(error);
                    })
                    .start();
        });
    }



    /**
     * 处理代码流：收集完整代码后解析并保存
     *
     * @param codeStream      代码流
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 代码流
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();

        return codeStream
                .doOnNext(codeBuilder::append)
                .doOnComplete(() -> {
                    try {
                        // 完整字符串的代码
                        String completeCode = codeBuilder.toString();
                        // String -> 格式化返回结构
                        Object parser = CodeParserExecutor.executeParser(completeCode,codeGenTypeEnum);
                        // 保存
                        File saveDir = CodeFileSaverExecutor.executeSaver(parser,codeGenTypeEnum,appId);
                        log.info("{} 代码生成并保存完成，保存目录：{}", codeGenTypeEnum.getValue(), saveDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存失败: {}", e.getMessage());
                    }
                });

    }


    /**
     * 统一入口：根据类型生成并保存代码
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId);

        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

}
