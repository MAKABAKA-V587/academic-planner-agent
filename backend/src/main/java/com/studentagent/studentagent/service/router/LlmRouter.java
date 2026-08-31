package com.studentagent.studentagent.service.router;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 第二级 LLM 路由（规则判不准时兜底）：一次极小调用（temperature 默认 + 输出仅一个词），
 * 判 TASK / CHAT。超时或异常返回 null，由 ChatRouter 默认 TOOL（安全超集）。
 */
@Slf4j
@Component
public class LlmRouter {

    private static final String ROUTER_PROMPT = """
            你是意图分类器。根据对话历史判断用户最新消息的意图，只输出一个词：TASK 或 CHAT。
            TASK = 需要操作日历、生成学习计划、安排复习、联网搜索等实际动作
            CHAT = 闲聊、问候、知识问答、情感交流、学习方法咨询
            只输出 TASK 或 CHAT，不要输出其他任何内容。""";

    private final ChatModel chatModel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-router-llm");
        t.setDaemon(true);
        return t;
    });

    public LlmRouter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** @return "TASK" / "CHAT"，失败返回 null（由上层默认 TOOL） */
    public String classify(List<String> recentTurns, String userMessage, long timeoutMs) {
        StringBuilder hist = new StringBuilder();
        if (recentTurns != null) {
            for (String turn : recentTurns) {
                hist.append(turn).append('\n');
            }
        }
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from(ROUTER_PROMPT),
                UserMessage.from((hist.isEmpty() ? "" : "【最近对话】\n" + hist + "\n") + "【最新消息】" + userMessage));
        try {
            return CompletableFuture
                    .supplyAsync(() -> {
                        String out = chatModel.chat(messages).aiMessage().text();
                        return out == null ? null : out.trim();
                    }, executor)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("[router] LLM路由失败，默认TOOL: {}", e.getMessage());
            return null;
        }
    }
}
