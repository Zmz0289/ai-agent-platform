package com.aiagent.agent.application;

/**
 * 流式对话事件，由 API 层序列化为 SSE 数据帧。
 *
 * <ul>
 *     <li>{@code token}：增量文本（可能多次）</li>
 *     <li>{@code done}：流结束，携带完整答案、explain 元数据</li>
 *     <li>{@code error}：生成失败</li>
 * </ul>
 */
public record ChatStreamEvent(String type,
                              String content,
                              String answer,
                              ChatExecutionResult result,
                              Object explain,
                              String message) {

    public static final String TYPE_TOKEN = "token";
    public static final String TYPE_DONE = "done";
    public static final String TYPE_ERROR = "error";

    public static ChatStreamEvent token(String content) {
        return new ChatStreamEvent(TYPE_TOKEN, content, null, null, null, null);
    }

    public static ChatStreamEvent done(String answer, ChatExecutionResult result) {
        return new ChatStreamEvent(TYPE_DONE, null, answer, result, null, null);
    }

    public static ChatStreamEvent done(String answer, ChatExecutionResult result, Object explain) {
        return new ChatStreamEvent(TYPE_DONE, null, answer, result, explain, null);
    }

    public static ChatStreamEvent error(String message) {
        return new ChatStreamEvent(TYPE_ERROR, null, null, null, null, message);
    }

    public boolean isToken() {
        return TYPE_TOKEN.equals(type);
    }
}
