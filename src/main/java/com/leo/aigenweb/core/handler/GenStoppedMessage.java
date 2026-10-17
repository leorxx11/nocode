package com.leo.aigenweb.core.handler;

import com.leo.aigenweb.constant.AppConstant;

/**
 * 停止生成时保存到对话历史的 AI 消息
 */
final class GenStoppedMessage {

    private static final String CODE_FENCE = "```";

    private GenStoppedMessage() {
    }

    /**
     * 已生成的部分加上停止提示。停在代码块中间时先补上代码块的结束标记，否则提示会被渲染进代码块里
     */
    static String of(String partialResponse) {
        long fenceCount = partialResponse.lines()
                .filter(line -> line.stripLeading().startsWith(CODE_FENCE))
                .count();
        String closed = fenceCount % 2 == 1 ? partialResponse + "\n" + CODE_FENCE : partialResponse;
        return closed + AppConstant.GEN_STOPPED_MARK;
    }
}
