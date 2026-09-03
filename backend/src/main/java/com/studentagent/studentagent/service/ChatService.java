package com.studentagent.studentagent.service;

import com.studentagent.studentagent.entity.ChatSessionMaterial;
import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.entity.StudyMaterial;
import com.studentagent.studentagent.mapper.ChatSessionMaterialMapper;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.SessionMapper;
import com.studentagent.studentagent.mapper.StudyMaterialMapper;
import com.studentagent.studentagent.service.chat.ChatContextBuilder;
import com.studentagent.studentagent.service.chat.ChatHistoryStore;
import com.studentagent.studentagent.service.chat.ChatLlmClient;
import com.studentagent.studentagent.service.chat.ChatPrompts;
import com.studentagent.studentagent.service.chat.ChatStreamOrchestrator;
import com.studentagent.studentagent.service.chat.TokenBudgetService;
import com.studentagent.studentagent.service.router.ChatRoute;
import com.studentagent.studentagent.service.router.ChatRouter;
import com.studentagent.studentagent.service.router.RouteDecision;
import com.studentagent.studentagent.tool.ToolContextHolder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话门面服务：路由分流 → 上下文组装 → 委托执行 → 收尾（记忆提取/历史写入）。
 * 细节职责拆分至 service.chat 包：
 * - ChatPrompts          提示词常量
 * - ChatHistoryStore     短时历史缓存 + 滚动摘要
 * - ChatContextBuilder   系统提示词构建 + 长时记忆召回
 * - ChatLlmClient        阻塞调用原语 + 工具循环
 * - ChatStreamOrchestrator 流式编排 + 工具状态事件协议
 * 另含会话参考资料与用户上传资料管理（独立子域）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatModel chatModel;
    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final ChatRouter chatRouter;
    private final MemoryExtractService memoryExtractService;
    private final ProfileService profileService;
    private final MemoryRecordMapper memoryRecordMapper;
    private final ChatSessionMaterialMapper chatSessionMaterialMapper;
    private final StudyMaterialMapper studyMaterialMapper;
    private final ChatHistoryStore historyStore;
    private final ChatContextBuilder contextBuilder;
    private final ChatLlmClient llmClient;
    private final ChatStreamOrchestrator streamOrchestrator;
    private final TokenBudgetService tokenBudget;

    private static final String BUSY_REPLY = "抱歉，当前服务繁忙，请稍后再试";

    /** 当日 token 额度用尽的提示文案（成本控制：不再调用 LLM，直接作为回复返回） */
    private static final String QUOTA_REPLY = "你今天的 AI 使用额度已用完，明天再来继续吧（也可联系管理员调整限额）";

    /** 没有可重新生成的消息提示文案 */
    private static final String NO_REGENERABLE = "没有可重新生成的消息";

    // ==================== 阻塞对话 ====================

    /**
     * 带短时记忆 + 长时记忆召回的多轮对话
     */
    public String chat(Long sessionId, String userMessage) {
        return chat(sessionId, userMessage, false);
    }

    /**
     * 带短时记忆 + 长时记忆召回的多轮对话（支持联网开关）
     */
    public String chat(Long sessionId, String userMessage, boolean webSearch) {
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        // 0. 成本控制：当日 token 额度用尽则不再调用 LLM，直接返回提示（占位回复同步历史保持一致）
        if (tokenBudget.exceeded(userId)) {
            saveFailureHistory(sessionId, userMessage, QUOTA_REPLY);
            return QUOTA_REPLY;
        }

        // 1. 获取历史消息列表（优先Redis，降级MySQL）
        List<Map<String, String>> history = historyStore.loadHistory(sessionId);

        // 2. 路由 Agent：规则优先判 SIMPLE/TOOL，判不准时 LLM 兜底（与流式带工具路径一致）
        RouteDecision decision = route(history, userMessage);

        // 3. 构建系统提示词（含长时记忆召回，联网提示）
        String systemPrompt = decision.route() == ChatRoute.SIMPLE
                ? contextBuilder.buildSimpleSystemPrompt(userId, sessionId, userMessage)
                : contextBuilder.buildSystemPrompt(userId, sessionId, userMessage);
        if (webSearch) {
            systemPrompt += ChatPrompts.WEBSEARCH_HINT;
        }

        // 4. 构建 LangChain4j 消息列表
        List<ChatMessage> aiMessages = buildAiMessages(systemPrompt, history);
        appendCurrentUserMessage(aiMessages, history, userMessage);

        // 5. 调用大模型（带工具循环 + 限流重试，设置线程上下文以便工具方法持久化消息）。
        //    enforceTool：路由判 TOOL（规则或 LLM 兜底）时，模型首轮不调工具则以 tool_choice=required 强制重试
        ToolContextHolder.set(sessionId, userId, webSearch);
        String reply;
        boolean enforceTool = decision.route() == ChatRoute.TOOL;
        try {
            reply = llmClient.callWithRateRetry(() -> llmClient.chatWithTools(aiMessages, enforceTool));
        } finally {
            // 工具循环多轮的 token 用量统一在此记账（成本控制），失败也取走防 ThreadLocal 残留
            tokenBudget.recordUsage(userId, llmClient.drainUsage());
            ToolContextHolder.clear();
        }
        if (reply == null) {
            // LLM 失败：用户消息已由 Controller 写入 MySQL，这里把失败占位回复同步写入 Redis，保持两份历史一致
            saveFailureHistory(sessionId, userMessage, BUSY_REPLY);
            return BUSY_REPLY;
        }

        // 6. 从回复中提取内联记忆（减少独立 LLM 调用），返回干净回复
        String cleanReply = processInlineMemory(userId, reply);

        // 7. 追加本轮消息到历史（存干净回复），截断为最近窗口轮数，写回Redis
        history.add(Map.of("role", "user", "content", userMessage));
        history.add(Map.of("role", "assistant", "content", cleanReply));
        historyStore.saveHistory(sessionId, truncate(history));

        return cleanReply;
    }

    // ==================== 流式对话 ====================

    /**
     * SSE 流式对话（真流式）：StreamingChatModel 的 token 边生成边推给前端，
     * 首 token 毫秒级到达，不再等整段生成完。调用方收集完整响应做记忆提取和缓存写入。
     * 流式调用失败时降级为阻塞 chat + 分块模拟流式（保底）。
     */
    public Flux<String> chatStream(Long sessionId, String userMessage, boolean webSearch) {
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        // 成本控制：当日额度用尽则不再调用 LLM
        if (tokenBudget.exceeded(userId)) {
            return Flux.just(QUOTA_REPLY);
        }

        List<Map<String, String>> history = historyStore.loadHistory(sessionId);
        String systemPrompt = contextBuilder.buildStreamSystemPrompt(userId, sessionId, userMessage, webSearch);

        List<ChatMessage> aiMessages = buildAiMessages(systemPrompt, history);
        appendCurrentUserMessage(aiMessages, history, userMessage);

        // 真流式：LLM token 边生成边推；异常降级为阻塞重试后分块模拟流式
        // （无工具执行，无需设置 ToolContextHolder）
        return streamOrchestrator.streamTokens(userId, aiMessages)
                .onErrorResume(e -> {
                    log.warn("流式对话调用失败，降级阻塞重试: {}", e.getMessage());
                    return streamOrchestrator.fallbackStream(userId, aiMessages);
                });
    }

    /**
     * 带工具调用的 SSE 流式对话（工具类消息专用）：
     * SIMPLE 路由 → 精简 prompt + 真流式（无工具规格）；
     * TOOL 路由 → 阻塞工具循环 + 状态事件流，拿到完整回复后分块模拟流式输出。
     */
    public Flux<String> chatStreamWithTools(Long sessionId, String userMessage, boolean webSearch) {
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        // 成本控制：当日额度用尽则不再调用 LLM
        if (tokenBudget.exceeded(userId)) {
            return Flux.just(QUOTA_REPLY);
        }

        List<Map<String, String>> history = historyStore.loadHistory(sessionId);

        // 路由 Agent：规则优先判 SIMPLE/TOOL，判不准时 LLM 兜底，失败默认 TOOL（= 现状行为）
        RouteDecision decision = route(history, userMessage);

        String systemPrompt;
        if (decision.route() == ChatRoute.SIMPLE) {
            systemPrompt = contextBuilder.buildSimpleSystemPrompt(userId, sessionId, userMessage);
        } else {
            systemPrompt = contextBuilder.buildSystemPrompt(userId, sessionId, userMessage);
            if (webSearch) {
                systemPrompt += ChatPrompts.WEBSEARCH_HINT;
            }
        }

        List<ChatMessage> aiMessages = buildAiMessages(systemPrompt, history);
        appendCurrentUserMessage(aiMessages, history, userMessage);

        // SIMPLE：精简 prompt + 真流式（不带工具规格，省 token 且无工具误触发）
        if (decision.route() == ChatRoute.SIMPLE) {
            return streamOrchestrator.streamTokens(userId, aiMessages)
                    .onErrorResume(e -> {
                        log.warn("SIMPLE路由流式调用失败，降级阻塞重试: {}", e.getMessage());
                        return streamOrchestrator.fallbackStream(userId, aiMessages);
                    });
        }

        // TOOL：规则路由已强命中工具意图（tool_verbs 等），直接阻塞工具循环，
        // 执行期间推送工具状态事件（"正在检索知识点…"等），拿到完整回复后分块模拟流式。
        // 跳过"带工具规格的流式试探"（该试探对工具类消息必然以工具降级告终，
        // 等于白跑一轮完整 LLM 调用），首字延迟约减半。
        // 失败降级链：工具循环 → 无工具阻塞重试（fallbackStream）→ 提示繁忙。
        // enforceTool：路由判 TOOL（规则或 LLM 兜底）时，模型首轮不调工具（幻觉"已添加"/直接编计划）则强制追问重试
        boolean enforceTool = decision.route() == ChatRoute.TOOL;
        return Flux.defer(() ->
                streamOrchestrator.toolLoopWithStatus(sessionId, userId, webSearch, enforceTool, aiMessages));
    }

    // ==================== 重新生成 ====================

    /**
     * 带工具调用的重新生成（流式）：基于最后一条用户消息重新回答。
     * 用户消息不重复落库，工具多轮在服务端内部完成，最终回答阶段流式输出。
     */
    public Flux<String> regenerateStream(Long sessionId, boolean webSearch) {
        Long lastUserMessageId = messageMapper.getLastUserMessageId(sessionId);
        if (lastUserMessageId == null) {
            return Flux.just(NO_REGENERABLE);
        }
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        // 成本控制：当日额度用尽则不再调用 LLM
        if (tokenBudget.exceeded(userId)) {
            return Flux.just(QUOTA_REPLY);
        }

        List<Map<String, String>> history = historyStore.loadHistory(sessionId);
        String lastUserContent = truncateToLastUserMessage(history);
        if (lastUserContent == null) {
            return Flux.just(NO_REGENERABLE);
        }

        // 保留 MySQL 中的旧版本回复（前端可切换查看），Redis 历史只保留最新版本
        historyStore.saveHistory(sessionId, history);

        String systemPrompt = contextBuilder.buildSystemPrompt(userId, sessionId, lastUserContent);
        if (webSearch) {
            systemPrompt += ChatPrompts.WEBSEARCH_HINT;
        }

        List<ChatMessage> aiMessages = buildAiMessages(systemPrompt, history);
        // history 已截断到包含末尾的用户消息（待重新生成的这条），不能重复追加
        appendCurrentUserMessage(aiMessages, history, lastUserContent);

        // 真流式优先（带工具规格）：秒出首字；模型请求工具时自动降级为阻塞工具循环
        // （工具上下文在 streamWithTools 内部、于执行工具循环的线程上设置）
        return streamOrchestrator.streamWithTools(sessionId, userId, webSearch, aiMessages);
    }

    /**
     * 流式重新生成后的收尾：提取内联记忆、更新历史缓存。
     * 用户消息已存在于历史中（regenerateStream 保留），不重复追加。
     */
    public String finishRegenerate(Long sessionId, Long userId, String fullReply) {
        String cleanReply = processInlineMemory(userId, fullReply);
        List<Map<String, String>> history = historyStore.loadHistory(sessionId);
        history.add(Map.of("role", "assistant", "content", cleanReply));
        historyStore.saveHistory(sessionId, truncate(history));
        return cleanReply;
    }

    public String regenerateReply(Long sessionId) {
        return regenerateReply(sessionId, false);
    }

    public String regenerateReply(Long sessionId, boolean webSearch) {
        Long lastUserMessageId = messageMapper.getLastUserMessageId(sessionId);
        if (lastUserMessageId == null) {
            return NO_REGENERABLE;
        }

        // 先读取最后一条用户消息内容，截断其后的历史（用户消息本身保留，regenerate 只替换它后面的回复）
        List<Map<String, String>> fullHistory = historyStore.loadHistory(sessionId);
        String lastUserContent = truncateToLastUserMessage(fullHistory);
        if (lastUserContent == null) {
            return NO_REGENERABLE;
        }

        // 保留 MySQL 中的旧版本回复（前端可切换查看），Redis 历史只保留最新版本
        historyStore.saveHistory(sessionId, fullHistory);

        // 获取用户ID用于记忆召回
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        // 成本控制：当日额度用尽则不再调用 LLM，占位回复同步两份历史
        if (tokenBudget.exceeded(userId)) {
            saveRegenerateFailureHistory(sessionId, QUOTA_REPLY);
            return QUOTA_REPLY;
        }

        String systemPrompt = contextBuilder.buildSystemPrompt(userId, sessionId, lastUserContent);
        if (webSearch) {
            systemPrompt += ChatPrompts.WEBSEARCH_HINT;
        }

        List<ChatMessage> aiMessages = buildAiMessages(systemPrompt, fullHistory);
        aiMessages.add(UserMessage.from(lastUserContent));

        // 调用大模型（带工具循环 + 限流重试，设置线程上下文以便工具方法持久化消息）
        ToolContextHolder.set(sessionId, userId, webSearch);
        String reply;
        try {
            reply = llmClient.callWithRateRetry(() -> llmClient.chatWithTools(aiMessages, false));
        } finally {
            // 工具循环多轮的 token 用量统一在此记账（成本控制），失败也取走防 ThreadLocal 残留
            tokenBudget.recordUsage(userId, llmClient.drainUsage());
            ToolContextHolder.clear();
        }
        if (reply == null) {
            // LLM 失败：用户消息仍在 MySQL 中，把失败占位回复同时写入 MySQL + Redis，保持两份历史一致
            com.studentagent.studentagent.entity.ChatMessage failMsg = new com.studentagent.studentagent.entity.ChatMessage();
            failMsg.setSessionId(sessionId);
            failMsg.setRole("assistant");
            failMsg.setContent(BUSY_REPLY);
            messageMapper.insert(failMsg);
            saveFailureHistory(sessionId, lastUserContent, BUSY_REPLY);
            return BUSY_REPLY;
        }

        // 提取内联记忆，存干净回复
        String cleanReply = processInlineMemory(userId, reply);

        com.studentagent.studentagent.entity.ChatMessage aiMsg = new com.studentagent.studentagent.entity.ChatMessage();
        aiMsg.setSessionId(sessionId);
        aiMsg.setRole("assistant");
        aiMsg.setContent(cleanReply);
        messageMapper.insert(aiMsg);

        fullHistory.add(Map.of("role", "assistant", "content", cleanReply));
        historyStore.saveHistory(sessionId, truncate(fullHistory));
        return cleanReply;
    }

    // ==================== 收尾：记忆提取 / 失败占位 / 历史维护 ====================

    /**
     * 流式响应后的收尾：提取内联记忆、更新历史缓存
     */
    public String finishStream(Long sessionId, Long userId, String userMessage, String fullReply) {
        String cleanReply = processInlineMemory(userId, ChatStreamOrchestrator.stripToolStatus(fullReply));
        List<Map<String, String>> history = historyStore.loadHistory(sessionId);
        history.add(Map.of("role", "user", "content", userMessage));
        history.add(Map.of("role", "assistant", "content", cleanReply));
        historyStore.saveHistory(sessionId, truncate(history));
        return cleanReply;
    }

    /**
     * LLM 调用失败时记录占位回复，保持 Redis 与 MySQL 历史一致。
     * 阻塞端点由 Controller 负责把用户消息和占位回复落库 MySQL，这里只同步 Redis；
     * 流式端点由 Controller 落库 MySQL 后调用本方法。
     */
    public void saveFailureHistory(Long sessionId, String userMessage, String placeholder) {
        saveFailureHistory(sessionId, userMessage, placeholder, true);
    }

    /**
     * 重新生成失败时：只追加失败占位回复（用户消息已在历史中，不重复追加）
     */
    public void saveRegenerateFailureHistory(Long sessionId, String placeholder) {
        saveFailureHistory(sessionId, null, placeholder, false);
    }

    private void saveFailureHistory(Long sessionId, String userMessage, String placeholder, boolean includeUser) {
        try {
            List<Map<String, String>> history = historyStore.loadHistory(sessionId);
            if (includeUser && userMessage != null) {
                history.add(Map.of("role", "user", "content", userMessage));
            }
            history.add(Map.of("role", "assistant", "content", placeholder));
            historyStore.saveHistory(sessionId, truncate(history));
        } catch (Exception e) {
            log.warn("写入失败占位历史失败: {}", e.getMessage());
        }
    }

    public void clearHistory(Long sessionId) {
        historyStore.clearHistory(sessionId);
    }

    public void rebuildHistory(Long sessionId) {
        historyStore.rebuildHistory(sessionId);
    }

    /**
     * 异步用 AI 生成会话标题（独立 LLM 调用，token 用量同样计入成本控制）
     */
    @Async("memoryExtractExecutor")
    public void generateTitleAsync(Long sessionId, String firstMessage) {
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;
        // 成本控制：当日额度用尽则跳过标题生成，直接用首条消息兜底
        if (tokenBudget.exceeded(userId)) {
            setFallbackTitle(sessionId, firstMessage);
            return;
        }
        try {
            var response = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("用6-10个字概括用户的问题作为对话标题，只输出标题，不要带引号和标点。"),
                            UserMessage.from(firstMessage)))
                    .build());
            tokenBudget.recordUsage(userId, TokenBudgetService.Usage.fromChat(response.tokenUsage()));
            String result = response.aiMessage() != null ? response.aiMessage().text() : null;
            if (result != null && !result.isBlank()) {
                String title = result.trim();
                if (title.length() > 20) title = title.substring(0, 20);
                sessionMapper.autoTitle(sessionId, title);
                log.info("AI生成标题: session={}, title={}", sessionId, title);
            }
        } catch (Exception e) {
            // 失败就用首条消息前15字兜底
            setFallbackTitle(sessionId, firstMessage);
        }
    }

    /** 标题兜底：取首条消息前15字直接作为会话标题 */
    private void setFallbackTitle(Long sessionId, String firstMessage) {
        String fallback = firstMessage != null && firstMessage.length() > 15
                ? firstMessage.substring(0, 15) : (firstMessage != null ? firstMessage : "新对话");
        sessionMapper.autoTitle(sessionId, fallback);
        log.debug("AI标题使用兜底: {}", fallback);
    }

    // ==================== 内部工具方法 ====================

    /** 路由 Agent：取最近 4 条历史作为上下文，规则优先判 SIMPLE/TOOL，判不准时 LLM 兜底 */
    private RouteDecision route(List<Map<String, String>> history, String userMessage) {
        List<String> recentTurns = history.subList(Math.max(0, history.size() - 4), history.size()).stream()
                .map(m -> ("assistant".equals(m.get("role")) ? "AI：" : "用户：") + m.get("content"))
                .collect(Collectors.toList());
        return chatRouter.route(recentTurns, userMessage);
    }

    /** 历史消息 → LangChain4j 消息列表（含系统提示词），工具过程消息已在上游过滤 */
    private List<ChatMessage> buildAiMessages(String systemPrompt, List<Map<String, String>> history) {
        List<ChatMessage> aiMessages = new ArrayList<>();
        aiMessages.add(SystemMessage.from(systemPrompt));
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("assistant".equals(role)) {
                aiMessages.add(AiMessage.from(content));
            } else if ("user".equals(role)) {
                aiMessages.add(UserMessage.from(content));
            }
        }
        return aiMessages;
    }

    /**
     * 追加当前用户消息（防重复守卫）。
     * Controller 调用本服务前已把用户消息落库，loadHistory 读出的历史末尾通常已包含这条消息；
     * 若再无条件 append，会产生两条连续相同的 user 消息 —— SiliconFlow 的 DeepSeek-V3 对此
     * 必然返回 content=""（finish_reason=stop）的空响应（直连实测 100% 复现），外部表现为
     * 工具不被调用、回复为空、甚至输出乱码。历史末尾已是相同 user 消息时跳过追加。
     */
    private void appendCurrentUserMessage(List<ChatMessage> aiMessages,
                                          List<Map<String, String>> history,
                                          String userMessage) {
        boolean tailIsCurrent = !history.isEmpty()
                && "user".equals(history.get(history.size() - 1).get("role"))
                && userMessage.equals(history.get(history.size() - 1).get("content"));
        if (!tailIsCurrent) {
            aiMessages.add(UserMessage.from(userMessage));
        }
    }

    /** 历史截断到最近窗口轮数（ChatHistoryStore.MAX_ROUNDS） */
    private List<Map<String, String>> truncate(List<Map<String, String>> history) {
        int limit = ChatHistoryStore.MAX_ROUNDS * 2;
        if (history.size() > limit) {
            return history.subList(history.size() - limit, history.size());
        }
        return history;
    }

    /** 截断历史到"最后一条用户消息"（含），返回该消息内容；无用户消息返回 null（regenerate 用） */
    private String truncateToLastUserMessage(List<Map<String, String>> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).get("role"))) {
                String content = history.get(i).get("content");
                List<Map<String, String>> kept = history.subList(0, i + 1);
                history.clear();
                history.addAll(kept);
                return content;
            }
        }
        return null;
    }

    /**
     * 从 LLM 回复中解析 ---MEMORY--- 块，调用 MemoryExtractService 去重存储，
     * 异步触发标签生成，返回剥离记忆块后的干净回复。无块则原样返回。
     */
    private String processInlineMemory(Long userId, String reply) {
        if (userId == null || reply == null) {
            return reply != null ? reply : "";
        }

        String marker = "---MEMORY---";
        String endMarker = "---END---";

        int startIdx = reply.indexOf(marker);
        if (startIdx == -1) {
            return reply;
        }

        // 防泄露兜底：即使 AI 只写了 ---MEMORY--- 未写 ---END---（格式漂移/被截断），
        // 也必须把 marker 至末尾整块剥离，绝不把记忆块暴露给用户。
        int endIdx = reply.indexOf(endMarker, startIdx);
        String memBlock;
        String beforeBlock = reply.substring(0, startIdx);
        String afterBlock = "";
        if (endIdx == -1) {
            log.debug("用户{}的回复含---MEMORY---但无---END---，按到末尾剥离防泄露", userId);
            memBlock = reply.substring(startIdx + marker.length()).trim();
        } else {
            memBlock = reply.substring(startIdx + marker.length(), endIdx).trim();
            afterBlock = reply.substring(endIdx + endMarker.length());
        }
        String cleanReply = (beforeBlock + afterBlock).replaceAll("\\n{3,}", "\n\n").trim();

        if (!memBlock.isEmpty() && !memBlock.equals("无")) {
            try {
                int count = memoryExtractService.processAndStore(userId, memBlock);
                if (count > 0) {
                    log.info("用户{}内联记忆提取成功 +{}条，异步触发标签更新", userId, count);
                    profileService.generateTagsAsync(userId);
                }
            } catch (Exception e) {
                log.warn("用户{}内联记忆处理失败: {}", userId, e.getMessage());
            }
        }

        return cleanReply;
    }

    // ==================== 会话参考资料（选择资料库文件挂到会话，AI 对话时参考） ====================

    /** 当前会话已启用的参考资料列表（元信息，供前端展示） */
    public List<Map<String, Object>> listSessionMaterials(Long userId, Long sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatSessionMaterial rel : chatSessionMaterialMapper.findBySessionId(sessionId)) {
            StudyMaterial m = studyMaterialMapper.findById(rel.getMaterialId());
            if (m == null || !m.getUserId().equals(userId)) continue;
            out.add(Map.of(
                    "materialId", m.getMaterialId(),
                    "fileName", m.getFileName(),
                    "fileSize", m.getFileSize()
            ));
        }
        return out;
    }

    /** 把资料库文件挂到当前会话（重复挂载自动忽略），返回是否新增 */
    public boolean addSessionMaterial(Long userId, Long sessionId, Long materialId) {
        StudyMaterial m = studyMaterialMapper.findById(materialId);
        if (m == null || !m.getUserId().equals(userId)) {
            throw new IllegalArgumentException("资料不存在");
        }
        ChatSessionMaterial rel = new ChatSessionMaterial();
        rel.setSessionId(sessionId);
        rel.setMaterialId(materialId);
        return chatSessionMaterialMapper.insert(rel) > 0;
    }

    /** 从会话移除参考资料；临时上传的资料（is_temp=1）一并物理删除 */
    public void removeSessionMaterial(Long userId, Long sessionId, Long materialId) {
        StudyMaterial m = studyMaterialMapper.findById(materialId);
        if (m != null && !m.getUserId().equals(userId)) {
            throw new IllegalArgumentException("资料不存在");
        }
        chatSessionMaterialMapper.delete(sessionId, materialId);
        // 临时上传：只在当前会话用，移除后直接清理，避免残留
        if (m != null && Integer.valueOf(1).equals(m.getIsTemp())) {
            try {
                studyMaterialMapper.deleteById(materialId, userId);
            } catch (Exception e) {
                log.warn("清理临时资料{}失败: {}", materialId, e.getMessage());
            }
        }
    }

    // ==================== 用户上传学习资料（轻量版：文本注入，不走向量库） ====================

    private static final String UPLOAD_PREFIX = "【上传资料】";
    private static final int UPLOAD_MAX_FILE_SIZE = 2 * 1024 * 1024;   // 2MB
    private static final int UPLOAD_MAX_CHARS = 5000;                  // 单文件最多保留字符数

    /**
     * 上传学习资料（.txt/.md/.csv）：读取纯文本写入长时记忆（带【上传资料】前缀），
     * 后续对话自动注入供 AI 参考。
     */
    public Map<String, Object> uploadFile(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件为空");
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) filename = "unnamed.txt";
        String lower = filename.toLowerCase();
        if (!(lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".csv"))) {
            throw new IllegalArgumentException("仅支持 .txt / .md / .csv 文本文件");
        }
        if (file.getSize() > UPLOAD_MAX_FILE_SIZE) throw new IllegalArgumentException("文件不能超过 2MB");
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("文件读取失败: " + e.getMessage());
        }
        content = content.trim();
        if (content.isEmpty()) throw new IllegalArgumentException("文件内容为空");
        if (content.length() > UPLOAD_MAX_CHARS) {
            content = content.substring(0, UPLOAD_MAX_CHARS) + "\n…（内容过长，已截断）";
        }
        MemoryRecord record = new MemoryRecord();
        record.setUserId(userId);
        record.setMemoryText(UPLOAD_PREFIX + filename + "\n" + content);
        // vector_id 列非空：上传资料不走向量库，用唯一占位符（避免与 Chroma 真实 id 冲突）
        record.setVectorId("upload-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 100000));
        memoryRecordMapper.insert(record);
        log.info("用户{}上传学习资料: {} ({}字符)", userId, filename, content.length());
        return Map.of("fileName", filename, "recordId", record.getRecordId(), "chars", content.length());
    }

    /** 查询用户已上传的资料列表（按最新在前） */
    public List<Map<String, Object>> listUploadedFiles(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MemoryRecord r : memoryRecordMapper.findByUserId(userId)) {
            if (r.getMemoryText() == null || !r.getMemoryText().startsWith(UPLOAD_PREFIX)) continue;
            String rest = r.getMemoryText().substring(UPLOAD_PREFIX.length());
            String name = rest;
            int nl = rest.indexOf('\n');
            if (nl > 0) name = rest.substring(0, nl);
            out.add(Map.of("recordId", r.getRecordId(), "fileName", name,
                    "createTime", r.getCreateTime() != null ? r.getCreateTime().toString() : ""));
        }
        out.sort((a, b) -> String.valueOf(b.get("createTime")).compareTo(String.valueOf(a.get("createTime"))));
        return out;
    }

    /** 删除一条上传资料（仅限本人且确属上传资料） */
    public int deleteUploadedFile(Long userId, Long recordId) {
        MemoryRecord r = memoryRecordMapper.findById(recordId);
        if (r == null || !r.getUserId().equals(userId)
                || r.getMemoryText() == null || !r.getMemoryText().startsWith(UPLOAD_PREFIX)) {
            return 0;
        }
        return memoryRecordMapper.deleteByIds(List.of(recordId));
    }
}
