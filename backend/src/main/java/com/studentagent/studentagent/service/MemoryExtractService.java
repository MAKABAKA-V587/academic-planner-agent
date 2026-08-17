package com.studentagent.studentagent.service;

import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 异步记忆提取服务（阶段3.2 + 3.3）
 * 每轮对话结束后从本轮消息中提取学习特征，去重后存入 Chroma + MySQL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractService {

    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final VectorStore vectorStore;
    private final MemoryRecordMapper memoryRecordMapper;
    private final MessageMapper messageMapper;
    private final ProfileService profileService;

    private static final String EXTRACT_KEY_PREFIX = "memory:extract:";
    private static final int EXTRACT_INTERVAL_SECONDS = 600; // 10分钟内不重复触发
    private static final double DEDUP_THRESHOLD = 0.9;

    /** 负面表述词（薄弱/不会/难等），用于记忆冲突覆盖的极性判断 */
    private static final String[] NEGATIVE_WORDS = {
            "薄弱", "不会", "不懂", "很差", "较差", "记不住", "搞不懂", "没掌握", "不行", "头疼", "困难", "不熟", "吃力"
    };

    /** 正面表述词（掌握/没问题/提高等），用于记忆冲突覆盖的极性判断 */
    private static final String[] POSITIVE_WORDS = {
            "掌握", "学会", "可以了", "还行", "没问题", "提高", "好转", "进步", "不错", "熟悉", "搞定", "会了", "懂了", "擅长", "解决"
    };

    private static final String EXTRACT_PROMPT = """
            你是一个学习特征提取器。仅从以下【用户消息】中提取用户的学习特征信息，
            【严禁】从AI回复等内容中提取，只提取用户自己表达的信息。
            输出格式：每条一行，格式为「类别-科目-具体描述」
            类别包括：薄弱科目、学习目标、考试计划、学习习惯、知识掌握、用户昵称
            如果没有新的学习特征可提取，只回复一个字「无」。
            示例输出：
            薄弱科目-高等数学-多元微积分计算能力不足
            考试计划-考研-2025年12月参加考研
            学习习惯-效率-偏好使用番茄钟学习法
            用户昵称-昵称-小明
            """;

    /**
     * 异步提取记忆（5分钟内同一用户不重复触发）
     * @param userMsg 用户消息
     * @param aiReply AI 回复（用于拼入提取上下文）
     */
    @Async("memoryExtractExecutor")
    public void extractMemory(Long userId, String userMsg, String aiReply) {
        String freqKey = EXTRACT_KEY_PREFIX + userId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(freqKey, "1", EXTRACT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            log.debug("用户{}在5分钟内已提取过记忆，跳过", userId);
            return;
        }

        // 拼接用户消息和 AI 回复作为提取上下文
        String context = "【用户消息】" + userMsg;
        if (aiReply != null && !aiReply.isBlank()) {
            context += "\n【AI回复摘要】" + (aiReply.length() > 300 ? aiReply.substring(0, 300) + "..." : aiReply);
        }

        try {
            String rawResult = doExtract(context);
            int newCount = processAndStore(userId, rawResult);
            if (newCount > 0) {
                log.info("用户{}记忆提取完成，新增{}条", userId, newCount);
                profileService.generateTags(userId);
            } else {
                log.debug("用户{}本轮无新增记忆", userId);
            }
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "";
            // 429 限流：不重试，等下一轮（10分钟后）再触发
            if (errMsg.contains("429") || errMsg.contains("rate limit") || errMsg.contains("50609")) {
                log.warn("用户{}记忆提取触发API限流，跳过本轮: {}", userId, errMsg);
                return;
            }
            log.error("用户{}记忆提取首次失败: {}", userId, e.getMessage());
            try {
                Thread.sleep(10000); // 等10秒再重试
                String rawResult = doExtract(userMsg);
                int newCount = processAndStore(userId, rawResult);
                if (newCount > 0) {
                    log.info("用户{}记忆提取重试成功，新增{}条", userId, newCount);
                    profileService.generateTags(userId);
                }
            } catch (Exception retryEx) {
                log.error("用户{}记忆提取重试仍失败，丢弃: {}", userId, retryEx.getMessage());
            }
        }
    }

    /**
     * 调用大模型提取记忆
     */
    private String doExtract(String conversationText) {
        return chatClient.prompt()
                .system(EXTRACT_PROMPT)
                .user(conversationText)
                .call()
                .content();
    }

    /**
     * 解析提取结果，去重后存储，公开供 ChatService 内联调用（走完整去重流程）
     * @return 新增记忆条数
     */
    public int processAndStore(Long userId, String rawResult) {
        return processAndStore(userId, rawResult, false);
    }

    /**
     * 解析提取结果，批量存储
     * @param skipDedup true=跳过 ChromaDB 去重（手动提取场景，由 MySQL UNIQUE 兜底）
     * @return 新增记忆条数
     */
    public int processAndStore(Long userId, String rawResult, boolean skipDedup) {
        if (rawResult == null || rawResult.isBlank() || rawResult.trim().equals("无")) {
            return 0;
        }

        // 1. 收集所有有效候选记忆，跳过格式不符的
        List<String> candidates = new ArrayList<>();
        for (String line : rawResult.trim().split("\n")) {
            String memoryText = line.trim();
            if (memoryText.isBlank() || memoryText.equals("无")) {
                continue;
            }
            if (memoryText.chars().filter(c -> c == '-').count() < 2) {
                log.debug("跳过格式不符的记忆: {}", memoryText);
                continue;
            }
            candidates.add(memoryText);
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        List<String> newItems;
        if (!skipDedup) {
            // 2. 批量去重：一次性查回用户全部已有记忆，内存比对
            List<Document> existingDocs;
            try {
                existingDocs = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(candidates.get(0) + " " + candidates.get(candidates.size() - 1))
                                .topK(50)
                                .filterExpression("userId == '" + userId + "' && type == 'extracted'")
                                .build()
                );
            } catch (Exception e) {
                log.warn("批量去重检索失败，按非重复处理: {}", e.getMessage());
                existingDocs = List.of();
            }

            newItems = new ArrayList<>();
            for (String candidate : candidates) {
                double best = existingDocs.stream()
                        .filter(d -> d.getScore() != null)
                        .mapToDouble(d -> cosineSim(d.getText(), candidate))
                        .max()
                        .orElse(0.0);
                if (best >= DEDUP_THRESHOLD) {
                    log.debug("跳过重复记忆: {}", candidate);
                    continue;
                }
                newItems.add(candidate);
            }
            if (newItems.isEmpty()) {
                return 0;
            }
            log.info("去重后剩余{}条待写入", newItems.size());
        } else {
            newItems = candidates;
        }

        // 3. 冲突覆盖：新声明与旧记忆同类别同科目且极性相反（或类别对立如 薄弱科目↔知识掌握）时，
        //    删除旧记忆让新声明生效——用户说"数学现在没问题了"应覆盖旧记忆"数学薄弱"
        try {
            List<MemoryRecord> allRecords = memoryRecordMapper.findByUserId(userId);
            if (!allRecords.isEmpty()) {
                List<Long> deleteIds = new ArrayList<>();
                List<String> deleteVectorIds = new ArrayList<>();
                for (String candidate : newItems) {
                    String[] cp = candidate.split("-", 3);
                    if (cp.length < 3) continue;
                    String cat = cp[0].trim(), subj = cp[1].trim(), desc = cp[2].trim();
                    for (MemoryRecord r : allRecords) {
                        if (deleteIds.contains(r.getRecordId())) continue;
                        String[] rp = r.getMemoryText().split("-", 3);
                        if (rp.length < 3) continue;
                        String rCat = rp[0].trim(), rSubj = rp[1].trim(), rDesc = rp[2].trim();
                        boolean conflict = false;
                        if (cat.equals(rCat) && subj.equals(rSubj)) {
                            conflict = oppositePolarity(rDesc, desc);
                        } else if (isOppositeCategory(cat, rCat) && subj.equals(rSubj)) {
                            conflict = oppositePolarity(rDesc, desc);
                        }
                        if (conflict) {
                            deleteIds.add(r.getRecordId());
                            if (r.getVectorId() != null) deleteVectorIds.add(r.getVectorId());
                            log.info("记忆冲突覆盖：新记忆「{}」覆盖旧记忆「{}」", candidate, r.getMemoryText());
                        }
                    }
                }
                if (!deleteVectorIds.isEmpty()) {
                    try {
                        vectorStore.delete(deleteVectorIds);
                        log.info("Chroma删除被覆盖记忆{}条", deleteVectorIds.size());
                    } catch (Exception e) {
                        log.warn("Chroma删除被覆盖记忆失败: {}", e.getMessage());
                    }
                }
                if (!deleteIds.isEmpty()) {
                    memoryRecordMapper.deleteByIds(deleteIds);
                }
            }
        } catch (Exception e) {
            log.warn("记忆冲突覆盖处理失败: {}", e.getMessage());
        }

        // 4. 批量写入 Chroma
        List<Document> docs = new ArrayList<>();
        List<MemoryRecord> records = new ArrayList<>();
        for (String text : newItems) {
            String docId = UUID.randomUUID().toString();
            docs.add(Document.builder()
                    .id(docId)
                    .text(text)
                    .metadata(Map.of("userId", String.valueOf(userId), "type", "extracted"))
                    .build());

            MemoryRecord record = new MemoryRecord();
            record.setUserId(userId);
            record.setMemoryText(text);
            record.setVectorId(docId);
            records.add(record);
        }

        try {
            vectorStore.add(docs);
            log.info("Chroma批量写入{}条成功", docs.size());
        } catch (Exception e) {
            log.error("Chroma批量写入失败: {}", e.getMessage());
            return 0;
        }

        // 4. 批量写 MySQL
        if (!records.isEmpty()) {
            memoryRecordMapper.batchInsert(records);
        }

        return newItems.size();
    }

    /** 简单的文本相似度比对（避免频繁调 Chroma） */
    private double cosineSim(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        java.util.Set<String> setA = new java.util.HashSet<>();
        java.util.Set<String> setB = new java.util.HashSet<>();
        for (String word : a.split("\\s+")) { setA.add(word); }
        for (String word : b.split("\\s+")) { setB.add(word); }
        java.util.Set<String> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);
        double dot = intersection.size();
        double normA = Math.sqrt(setA.size());
        double normB = Math.sqrt(setB.size());
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (normA * normB);
    }

    /**
     * 极性相反：一条描述表达负面（薄弱/不会），另一条表达正面（掌握/没问题）
     */
    private boolean oppositePolarity(String a, String b) {
        boolean aNeg = containsAny(a, NEGATIVE_WORDS);
        boolean aPos = containsAny(a, POSITIVE_WORDS);
        boolean bNeg = containsAny(b, NEGATIVE_WORDS);
        boolean bPos = containsAny(b, POSITIVE_WORDS);
        return (aNeg && bPos) || (aPos && bNeg);
    }

    /**
     * 类别对立：薄弱科目 ↔ 知识掌握（掌握声明推翻薄弱声明，反之亦然）
     */
    private boolean isOppositeCategory(String a, String b) {
        return ("薄弱科目".equals(a) && "知识掌握".equals(b))
                || ("知识掌握".equals(a) && "薄弱科目".equals(b));
    }

    private boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    /**
     * 手动触发记忆提取（阶段3.6），异步执行，忽略频率限制
     * 从用户最近的对话消息中提取学习特征，完成后自动更新画像标签
     */
    @Async("memoryExtractExecutor")
    public void extractNow(Long userId) {
        List<ChatMessage> recentMessages = messageMapper.findRecentByUserId(userId, 200);
        if (recentMessages.isEmpty()) {
            log.info("用户{}无对话记录，跳过手动提取", userId);
            return;
        }

        StringBuilder conversation = new StringBuilder();
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = recentMessages.get(i);
            if ("user".equals(msg.getRole())) {
                conversation.append(msg.getContent()).append("\n");
            }
        }

        try {
            String rawResult = doExtract(conversation.toString());
            int newCount = processAndStore(userId, rawResult, true);
            log.info("用户{}手动提取完成，新增{}条", userId, newCount);
            // 异步更新标签
            if (newCount > 0) {
                profileService.generateTagsAsync(userId);
            }
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "";
            if (errMsg.contains("429") || errMsg.contains("rate limit")) {
                log.warn("用户{}手动提取触发限流，3秒后重试一次", userId);
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
            log.error("用户{}手动提取首次失败: {}", userId, errMsg);
            try {
                Thread.sleep(2000);
                String rawResult = doExtract(conversation.toString());
                int newCount = processAndStore(userId, rawResult, true);
                log.info("用户{}手动提取重试成功，新增{}条", userId, newCount);
                if (newCount > 0) {
                    profileService.generateTagsAsync(userId);
                }
            } catch (Exception retryEx) {
                log.error("用户{}手动提取重试仍失败: {}", userId, retryEx.getMessage());
            }
        }
    }

    /**
     * 清除用户全部对话提取记忆（阶段3.6），仅清除 type=extracted，保留 type=profile 档案记忆
     */
    public void clearMemories(Long userId) {
        try {
            // 1. 从 Chroma 检索并删除所有 type=extracted 记忆
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("学习记忆")
                            .topK(200)
                            .filterExpression("userId == '" + userId + "' && type == 'extracted'")
                            .build()
            );
            if (!docs.isEmpty()) {
                List<String> docIds = docs.stream().map(Document::getId).collect(Collectors.toList());
                vectorStore.delete(docIds);
                // 同步删除 MySQL 中对应记录
                for (String docId : docIds) {
                    memoryRecordMapper.deleteByVectorId(docId);
                }
                log.info("用户{}清除动态记忆{}条", userId, docIds.size());
            } else {
                log.info("用户{}无动态记忆可清除", userId);
            }
        } catch (Exception e) {
            log.error("用户{}清除记忆失败: {}", userId, e.getMessage());
            throw new RuntimeException("清除记忆失败，请稍后重试");
        }
    }

    /**
     * 构造"完成学习任务"记忆文本（勾选/取消勾选共用同一格式，保证精确匹配删除）。
     * 文本附带任务日期，避免不同日期的同名任务互相误删。
     */
    private String buildCompletionText(String title, LocalDate date) {
        String t = title == null ? "" : title.trim();
        if (t.length() > 50) t = t.substring(0, 50);
        return date != null ? "知识掌握-学习任务-已完成：" + t + "（" + date + "）"
                : "知识掌握-学习任务-已完成：" + t;
    }

    /**
     * 勾选日历事件完成：写入"已完成任务"记忆（知识掌握类别）。
     * 走完整去重流程，同一任务反复勾选不会重复写入。
     */
    public void recordCompletion(Long userId, String title, LocalDate date) {
        if (userId == null || title == null || title.isBlank()) return;
        try {
            int n = processAndStore(userId, buildCompletionText(title, date), false);
            if (n > 0) {
                log.info("用户{}勾选完成，写入记忆: {}", userId, title);
            }
        } catch (Exception e) {
            log.warn("记录任务完成记忆失败: {}", e.getMessage());
        }
    }

    /**
     * 取消勾选：按文本精确删除对应的"已完成任务"记忆（同步删除 Chroma 向量与 MySQL 记录）。
     * 若同标题记忆已被后续对话改写为其它文本，则只删除精确匹配项。
     */
    public void removeCompletion(Long userId, String title, LocalDate date) {
        if (userId == null || title == null || title.isBlank()) return;
        String text = buildCompletionText(title, date);
        try {
            List<MemoryRecord> records = memoryRecordMapper.findByUserId(userId);
            List<Long> matchedIds = new ArrayList<>();
            for (MemoryRecord r : records) {
                if (text.equals(r.getMemoryText())) {
                    if (r.getVectorId() != null) {
                        try {
                            vectorStore.delete(List.of(r.getVectorId()));
                        } catch (Exception ve) {
                            log.warn("删除记忆向量失败: {}", ve.getMessage());
                        }
                    }
                    matchedIds.add(r.getRecordId());
                }
            }
            if (!matchedIds.isEmpty()) {
                memoryRecordMapper.deleteByIds(matchedIds);
                log.info("用户{}取消完成，删除完成记忆{}条", userId, matchedIds.size());
            }
        } catch (Exception e) {
            log.warn("取消完成记忆失败: {}", e.getMessage());
        }
    }

    /**
     * 获取用户全量记忆文本，用于画像标签生成（阶段3.6）
     */
    public List<String> getAllMemories(Long userId) {
        List<MemoryRecord> records = memoryRecordMapper.findByUserId(userId);
        return records.stream().map(MemoryRecord::getMemoryText).toList();
    }
}
