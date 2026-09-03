package com.studentagent.studentagent.service.chat;

import com.studentagent.studentagent.tool.ToolCallExecutor;
import com.studentagent.studentagent.tool.ToolContextHolder;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 流式编排器：真流式 token 推送 + 工具兜底降级链 + 工具执行状态事件协议。
 * 拆分自 ChatService，ChatService 按路由结果委托调用。
 *
 * 降级链：
 * ① 带工具规格真流式 → 模型请求调工具时降级阻塞工具循环（streamWithTools）
 * ② 工具循环失败 → 无工具阻塞重试（fallbackStream）
 * ③ 再失败 → 提示繁忙文案
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamOrchestrator {

    private final StreamingChatModel streamingChatModel;
    private final ChatLlmClient llmClient;
    private final ToolCallExecutor toolCallExecutor;
    private final TokenBudgetService tokenBudget;

    /** 将完整回复按小块切分，块间加固定延迟模拟逐字输出（前端无需改动）。
     *  延迟是关键：零间隔的 Flux 会被浏览器/JS 事件循环一次性吞掉，视觉上变成"整段输出"。
     *  块 4 字符 + 10ms 间隔是"流式感"与"总时长"的折中：500 字回复约多花 1.2s。 */
    private static final Duration CHUNK_INTERVAL = Duration.ofMillis(10);
    private static final int CHUNK_SIZE = 4;

    // ==================== 工具执行状态事件（流式端点专用） ====================
    // 阻塞工具循环执行期间（检索/生成计划/写入日历等，可达数秒到十几秒），
    // 向流中推送状态行让前端实时展示"正在做什么"，消除工具阶段的视觉空白。
    // 协议：以零宽空格开头的单行事件，前端按行剥离渲染为斜体状态提示，
    // 正文 token 到达后自然替换；落库/入历史前由 stripToolStatus 剔除。

    /** 工具状态事件行首标记（零宽空格，正常 LLM 输出不会出现） */
    public static final String TOOL_STATUS_MARK = "\u200B";

    /** 工具方法名 → 前端状态文案 */
    private static final Map<String, String> TOOL_STATUS_TEXT = Map.ofEntries(
            Map.entry("searchKnowledge", "正在检索知识点…"),
            Map.entry("webSearch", "正在搜索网络资料…"),
            Map.entry("generateStudyPlan", "正在生成学习计划…"),
            Map.entry("scheduleReviewPlan", "正在生成复习计划…"),
            Map.entry("queryEvents", "正在查询日历安排…"),
            Map.entry("addEvent", "正在写入日历…"),
            Map.entry("deleteEvent", "正在删除日历事件…"),
            Map.entry("clearToday", "正在清空今日日程…"),
            Map.entry("clearAll", "正在清空全部日程…"),
            Map.entry(ChatLlmClient.EVENT_GENERATING, "正在整理回答…"),
            Map.entry(ChatLlmClient.EVENT_THINKING, "正在分析请求…")
    );

    /** 生成一条状态事件行：零宽空格 + 文案 + 换行 */
    private static String toolStatusLine(String toolName) {
        return TOOL_STATUS_MARK + "⏳ " + TOOL_STATUS_TEXT.getOrDefault(toolName, "正在调用工具…") + "\n";
    }

    /** 剔除回复中的工具状态事件行（落库/入历史/记忆提取前调用） */
    public static String stripToolStatus(String reply) {
        if (reply == null || reply.isEmpty()) return reply;
        return reply.replaceAll("(?m)^\u200B[^\n]*\n?", "").stripLeading();
    }

    /** 把 StreamingChatModel 回调式 API 转换为 Flux<String>（token 逐个推）。
     *  userId 用于在 onCompleteResponse 里按最终 ChatResponse 的 tokenUsage 记账（成本控制）。 */
    public Flux<String> streamTokens(Long userId, List<ChatMessage> messages) {
        return Flux.create(sink -> streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial != null && !partial.isEmpty()) {
                    sink.next(partial);
                }
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                if (partialResponse != null && partialResponse.text() != null && !partialResponse.text().isEmpty()) {
                    sink.next(partialResponse.text());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                tokenBudget.recordUsage(userId, TokenBudgetService.Usage.fromChat(response.tokenUsage()));
                sink.complete();
            }

            @Override
            public void onError(Throwable error) {
                sink.error(error);
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    /** 降级保底：流式失败时用阻塞模型生成整段，分块模拟流式发出（阻塞调用的 token 用量在此记账） */
    public Flux<String> fallbackStream(Long userId, List<ChatMessage> messages) {
        try {
            String reply = llmClient.callWithRateRetry(() -> llmClient.chatNoTools(messages));
            tokenBudget.recordUsage(userId, llmClient.drainUsage());
            if (reply == null || reply.isBlank()) {
                reply = "抱歉，当前服务繁忙，请稍后再试";
            }
            return chunked(reply);
        } catch (Exception ex) {
            log.error("降级阻塞调用失败: {}", ex.getMessage());
            // 失败也取走累计值（可能有成功轮次已被计费），避免 ThreadLocal 残留
            tokenBudget.recordUsage(userId, llmClient.drainUsage());
            return Flux.just("抱歉，当前服务繁忙，请稍后再试");
        }
    }

    /** 流式优先 + 工具兜底：先真流式生成（带工具规格，秒出首字）；
     *  若模型在流式中请求调用工具（工具场景，通常不输出文本），
     *  则丢弃流式结果，降级为阻塞工具循环执行后分块模拟流式输出。 */
    public Flux<String> streamWithTools(Long sessionId, Long userId, boolean webSearch,
                                        List<ChatMessage> messages) {
        return Flux.<String>create(sink -> {
            List<ToolSpecification> specs = toolCallExecutor.specifications();
            streamingChatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs)
                    .build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partial) {
                    if (partial != null && !partial.isEmpty()) {
                        sink.next(partial);
                    }
                }

                @Override
                public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                    if (partialResponse != null && partialResponse.text() != null && !partialResponse.text().isEmpty()) {
                        sink.next(partialResponse.text());
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    if (response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests()) {
                        sink.error(new ToolCallDetectedException());
                    } else {
                        sink.complete();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    sink.error(error);
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER)
        .onErrorResume(e -> {
            if (e instanceof ToolCallDetectedException) {
                log.info("流式响应请求调用工具，降级为阻塞工具循环");
                try {
                    return chunked(callWithToolContext(sessionId, userId, webSearch, false, messages));
                } catch (Exception ex) {
                    log.warn("工具循环调用失败: {}", ex.getMessage());
                    return Flux.just("抱歉，当前服务繁忙，请稍后再试");
                }
            }
            log.warn("流式调用失败: {}", e.getMessage());
            // 网络类瞬时失败（closed/timeout 等）不能直接报繁忙：
            // 降级为无工具阻塞重试（fallbackStream 内部已带限流/空回复重试）
            return fallbackStream(userId, messages);
        });
    }

    /** 标记流式响应中检测到工具调用请求（触发降级为阻塞工具循环） */
    private static class ToolCallDetectedException extends RuntimeException {
        public ToolCallDetectedException() {
            super("streaming response contains tool calls");
        }
    }

    /**
     * 在"实际执行工具循环的线程"上设置/清理工具上下文。
     * Flux 链路中订阅线程（boundedElastic，由 Controller 的 subscribeOn 指定）与
     * Tomcat 请求线程不同，ThreadLocal 上下文必须在真正跑 chatWithTools 的线程内
     * set/clear，否则工具方法读到 userId=null，返回"无法获取用户信息"。
     */
    public String callWithToolContext(Long sessionId, Long userId, boolean webSearch,
                                      boolean enforceTool, List<ChatMessage> messages) {
        return callWithToolContext(sessionId, userId, webSearch, enforceTool, messages, null);
    }

    public String callWithToolContext(Long sessionId, Long userId, boolean webSearch,
                                      boolean enforceTool, List<ChatMessage> messages,
                                      Consumer<String> onToolStart) {
        ToolContextHolder.set(sessionId, userId, webSearch);
        try {
            return llmClient.callWithRateRetry(() -> llmClient.chatWithTools(messages, enforceTool, onToolStart));
        } finally {
            ToolContextHolder.clear();
        }
    }

    /**
     * 阻塞工具循环 + 状态事件流：工具执行与评审期间实时推送状态行，
     * 拿到完整回复后分块模拟流式输出。循环失败降级为无工具阻塞重试（fallbackStream）。
     */
    public Flux<String> toolLoopWithStatus(Long sessionId, Long userId, boolean webSearch,
                                           boolean enforceTool, List<ChatMessage> messages) {
        return Flux.<String>create(sink -> {
            try {
                // 先推"分析请求"状态：路由命中工具意图后，首轮 LLM 决定调哪个工具也需数秒
                sink.next(toolStatusLine(ChatLlmClient.EVENT_THINKING));
                String reply = callWithToolContext(sessionId, userId, webSearch, enforceTool, messages,
                        event -> sink.next(toolStatusLine(event)));
                chunked(reply).subscribe(sink::next, t -> sink.complete(), sink::complete);
            } catch (Exception ex) {
                log.warn("工具循环调用失败，降级无工具重试: {}", ex.getMessage());
                fallbackStream(userId, messages).subscribe(sink::next, t -> sink.complete(), sink::complete);
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    public Flux<String> chunked(String reply) {
        List<String> chunks = new ArrayList<>();
        if (reply != null && !reply.isEmpty()) {
            for (int i = 0; i < reply.length(); i += CHUNK_SIZE) {
                chunks.add(reply.substring(i, Math.min(i + CHUNK_SIZE, reply.length())));
            }
        }
        if (chunks.isEmpty()) {
            chunks.add("抱歉，当前服务繁忙，请稍后再试");
        }
        return Flux.fromIterable(chunks)
                .concatMap(chunk -> Mono.just(chunk).delayElement(CHUNK_INTERVAL));
    }
}
