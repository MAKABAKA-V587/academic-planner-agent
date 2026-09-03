package com.studentagent.studentagent.service.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.SessionMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 对话历史存储：短时历史（Redis 优先，MySQL 降级回源）+ 旧轮次滚动摘要压缩。
 * 拆分自 ChatService，ChatService 与 ChatContextBuilder 按需调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final ChatModel chatModel;

    public static final String HISTORY_KEY_PREFIX = "chat:history:";
    public static final int MAX_ROUNDS = 8;
    private static final int CACHE_TTL_SECONDS = 3600;

    // ========== 方案A：旧轮次滚动摘要压缩 ==========
    private static final String SUMMARY_KEY_PREFIX = "chat:summary:";
    private static final String SUMMARY_LOCK_PREFIX = "chat:summary:lock:";
    private static final int SUMMARY_TTL_SECONDS = 7 * 24 * 3600;   // 摘要缓存7天，冷会话不常驻
    private static final int SUMMARY_BATCH_ROUNDS = 3;              // 滑出窗口≥3轮就触发一次摘要（去抖）
    private static final int SUMMARY_MAX_CHARS = 500;               // 摘要硬截断，防模型输出失控
    private static final int SUMMARY_RENDER_LIMIT = 60;             // 单批最多渲染60条原文，防超长

    /** 摘要专用单线程守护执行器：串行化摘要任务（同会话天然互斥），绝不阻塞对话主链路 */
    private final java.util.concurrent.ExecutorService summaryExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "chat-summary");
                t.setDaemon(true);
                return t;
            });

    public List<Map<String, String>> loadHistory(Long sessionId) {
        String key = HISTORY_KEY_PREFIX + sessionId;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
            }
        } catch (Exception e) {
            log.warn("Redis读取历史失败，降级MySQL: {}", e.getMessage());
        }
        return loadFromMySQL(sessionId);
    }

    public void saveHistory(Long sessionId, List<Map<String, String>> history) {
        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(
                    HISTORY_KEY_PREFIX + sessionId,
                    json,
                    CACHE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("Redis写入历史失败: {}", e.getMessage());
        }
        // 方案A：每轮写历史后检查是否有滑出窗口的轮次需要滚动摘要（主链路只做 O(1) 检查）
        summarizeIfNeeded(sessionId);
    }

    private List<Map<String, String>> loadFromMySQL(Long sessionId) {
        List<com.studentagent.studentagent.entity.ChatMessage> messages = messageMapper.findBySessionId(sessionId);
        List<Map<String, String>> history = new ArrayList<>();
        // 每轮（一条 user 消息）可能有多条 assistant 回复（重新生成时保留的旧版本），
        // 回放历史时每轮只保留最后一条（最新版本），旧版本仅用于前端切换展示
        com.studentagent.studentagent.entity.ChatMessage pendingAssistant = null;
        for (com.studentagent.studentagent.entity.ChatMessage msg : messages) {
            String role = msg.getRole();
            // 工具调用/结果为模型内部过程消息，回放历史时过滤，避免污染用户上下文
            if ("tool_call".equals(role) || "tool_result".equals(role)) continue;
            if ("assistant".equals(role)) {
                pendingAssistant = msg;
                continue;
            }
            if ("user".equals(role)) {
                if (pendingAssistant != null) {
                    history.add(Map.of("role", "assistant", "content", pendingAssistant.getContent()));
                    pendingAssistant = null;
                }
                history.add(Map.of("role", "user", "content", msg.getContent()));
            }
        }
        if (pendingAssistant != null) {
            history.add(Map.of("role", "assistant", "content", pendingAssistant.getContent()));
        }
        if (history.size() > MAX_ROUNDS * 2) {
            history = history.subList(history.size() - MAX_ROUNDS * 2, history.size());
        }
        saveHistory(sessionId, history);
        return history;
    }

    public void clearHistory(Long sessionId) {
        try {
            redisTemplate.delete(HISTORY_KEY_PREFIX + sessionId);
            redisTemplate.delete(SUMMARY_KEY_PREFIX + sessionId); // 会话清空/删除时摘要一并清理
        } catch (Exception e) {
            log.warn("清除Redis历史失败: {}", e.getMessage());
        }
    }

    public void rebuildHistory(Long sessionId) {
        try {
            redisTemplate.delete(HISTORY_KEY_PREFIX + sessionId);
            loadFromMySQL(sessionId);
        } catch (Exception e) {
            log.warn("重建Redis历史失败: {}", e.getMessage());
        }
        // 消息删除/截断后，若窗口外已无内容（总轮数≤窗口），摘要必然滞留已删除轮次的信息 → 重置
        try {
            if (sessionMapper.findById(sessionId) != null
                    && messageMapper.getWindowStartMessageId(sessionId, MAX_ROUNDS) == null
                    && loadSummaryState(sessionId) != null) {
                redisTemplate.delete(SUMMARY_KEY_PREFIX + sessionId);
                sessionMapper.clearSummary(sessionId);
                log.info("会话{}窗口外已无内容，滚动摘要已重置", sessionId);
            }
        } catch (Exception e) {
            log.warn("摘要重置检查失败: {}", e.getMessage());
        }
    }

    // ========== 方案A：旧轮次滚动摘要压缩 ==========

    /** 摘要状态：text=摘要正文，upTo=水位线（已覆盖到该 message_id） */
    public record SummaryState(String text, long upTo) {}

    /**
     * 摘要触发检查：滑出窗口且未摘要的轮次 ≥ SUMMARY_BATCH_ROUNDS 时异步滚动摘要。
     * 每轮对话只做 1 次 Redis GET + 2 条轻量 SQL，摘要在独立线程执行，主链路零阻塞。
     */
    private void summarizeIfNeeded(Long sessionId) {
        try {
            SummaryState state = loadSummaryState(sessionId);
            Long windowStart = messageMapper.getWindowStartMessageId(sessionId, MAX_ROUNDS);
            if (windowStart == null) {
                return; // 总轮数未超过窗口，无滑出内容
            }
            long fromId = state != null ? state.upTo() : 0L;
            if (windowStart <= fromId) {
                return; // 水位线已覆盖窗口起点，无待摘内容
            }
            int pendingRounds = messageMapper.countUserMessagesBetween(sessionId, fromId, windowStart);
            if (pendingRounds < SUMMARY_BATCH_ROUNDS) {
                return; // 去抖：滑出不足6轮，攒批后一次摘要，摊薄 LLM 调用
            }
            // Redis SETNX 锁防多实例并发双摘（单实例下 summaryExecutor 串行已互斥）
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                    SUMMARY_LOCK_PREFIX + sessionId, "1", 60, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(locked)) {
                return;
            }
            final long from = fromId;
            final long to = windowStart;
            summaryExecutor.execute(() -> doSummarize(sessionId, from, to));
        } catch (Exception e) {
            log.warn("摘要触发检查失败: {}", e.getMessage());
        }
    }

    /**
     * 滚动摘要：滑出段原文 + 旧摘要 → LLM 合并 → 新摘要写 Redis + MySQL，水位线推进到窗口起点。
     * 失败不推进水位线，下一轮自然重试；任何异常都不影响对话本身。
     */
    private void doSummarize(Long sessionId, long fromId, long toId) {
        try {
            List<com.studentagent.studentagent.entity.ChatMessage> batch =
                    messageMapper.findSummarizableBetween(sessionId, fromId, toId, SUMMARY_RENDER_LIMIT);
            if (batch.isEmpty()) {
                // 无可摘要原文（可能已被删除）：直接推进水位线，避免空转
                String old = loadSummaryState(sessionId) != null ? loadSummaryState(sessionId).text() : "";
                saveSummaryState(sessionId, old, toId);
                return;
            }
            String oldSummary = loadSummaryState(sessionId) != null ? loadSummaryState(sessionId).text() : "";
            String rendered = batch.stream()
                    .map(m -> ("user".equals(m.getRole()) ? "用户：" : "AI：") + m.getContent())
                    .collect(Collectors.joining("\n"));
            String payload = "【已有摘要】\n" + (oldSummary.isEmpty() ? "（无）" : oldSummary)
                    + "\n\n【新增对话】\n" + rendered;
            String summary = chatModel.chat(List.of(
                            SystemMessage.from(ChatPrompts.SUMMARY_PROMPT),
                            UserMessage.from(payload)))
                    .aiMessage().text();
            if (summary == null || summary.isBlank()) {
                log.warn("会话{}摘要返回为空，水位线不推进，下轮重试", sessionId);
                return;
            }
            if (summary.length() > SUMMARY_MAX_CHARS) {
                summary = summary.substring(0, SUMMARY_MAX_CHARS);
            }
            saveSummaryState(sessionId, summary, toId);
            log.info("会话{}滚动摘要完成，水位线推进至 message_id={}，长度{}字",
                    sessionId, toId, summary.length());
        } catch (Exception e) {
            log.warn("会话{}摘要生成失败，保留旧水位线: {}", sessionId, e.getMessage());
        } finally {
            try {
                redisTemplate.delete(SUMMARY_LOCK_PREFIX + sessionId);
            } catch (Exception ignored) {
            }
        }
    }

    /** 读摘要状态：Redis 优先（7天TTL），降级 MySQL chat_session 持久化列 */
    public SummaryState loadSummaryState(Long sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(SUMMARY_KEY_PREFIX + sessionId);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, SummaryState.class);
            }
        } catch (Exception ignored) {
        }
        try {
            var s = sessionMapper.findById(sessionId);
            if (s != null && s.getSummary() != null && !s.getSummary().isEmpty()) {
                long upTo = s.getSummaryUpTo() != null ? s.getSummaryUpTo() : 0L;
                return new SummaryState(s.getSummary(), upTo);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 写摘要状态：Redis（TTL 7天）+ MySQL 双写，Redis 失效后可从 MySQL 恢复 */
    private void saveSummaryState(Long sessionId, String text, long upTo) {
        try {
            String json = objectMapper.writeValueAsString(new SummaryState(text, upTo));
            redisTemplate.opsForValue().set(SUMMARY_KEY_PREFIX + sessionId, json, SUMMARY_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("摘要写入Redis失败: {}", e.getMessage());
        }
        try {
            sessionMapper.updateSummary(sessionId, text, upTo);
        } catch (Exception e) {
            log.warn("摘要写入MySQL失败: {}", e.getMessage());
        }
    }
}
