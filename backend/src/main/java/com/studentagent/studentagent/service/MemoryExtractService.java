package com.studentagent.studentagent.service;

import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
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

    private final ChatModel chatModel;
    private final StringRedisTemplate redisTemplate;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
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
        return chatModel.chat(ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(EXTRACT_PROMPT),
                        UserMessage.from(conversationText)))
                .build())
                .aiMessage().text();
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
            // 2a. 文本精确去重（兜底：覆盖完全相同文本与无向量记忆）
            java.util.Set<String> existingTexts = memoryRecordMapper.findByUserId(userId).stream()
                    .map(MemoryRecord::getMemoryText)
                    .collect(Collectors.toSet());

            // 2b. 向量语义去重：embedAll 一次嵌入全部候选，再逐条对 Chroma 做 top-1 检索，
            //     用向量余弦分数判重（原文本分词余弦对中文失效——整句无空格等价于精确匹配才判重）
            List<Embedding> candEmbeddings;
            try {
                List<TextSegment> candSegments = candidates.stream()
                        .map(TextSegment::from).collect(Collectors.toList());
                candEmbeddings = embeddingModel.embedAll(candSegments).content();
            } catch (Exception e) {
                log.warn("候选记忆嵌入失败，降级为仅文本精确去重: {}", e.getMessage());
                candEmbeddings = List.of();
            }

            newItems = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                String candidate = candidates.get(i);
                if (existingTexts.contains(candidate)) {
                    log.debug("跳过完全重复记忆: {}", candidate);
                    continue;
                }
                if (!candEmbeddings.isEmpty()
                        && top1Score(candEmbeddings.get(i), userId) >= DEDUP_THRESHOLD) {
                    log.debug("向量判重跳过记忆: {}", candidate);
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
                        embeddingStore.removeAll(deleteVectorIds);
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

        // 4. MySQL 先行落库（vector_id 置空），Chroma 写成功后回填；
        //    Chroma 失败不丢记忆，由补偿任务按 vector_id IS NULL 补写
        try {
            List<MemoryRecord> records = new ArrayList<>();
            for (String text : newItems) {
                MemoryRecord record = new MemoryRecord();
                record.setUserId(userId);
                record.setMemoryText(text);
                records.add(record);
            }
            memoryRecordMapper.batchInsert(records);

            // 5. Chroma 批量写入：embedAll 一次嵌入；createTime 进 metadata，
            //    召回端直接从 metadata 做时间标注，省掉每请求的 MySQL 全表扫描
            List<String> ids = new ArrayList<>();
            List<TextSegment> segments = new ArrayList<>();
            String createTimeMs = String.valueOf(System.currentTimeMillis());
            for (String text : newItems) {
                String docId = UUID.randomUUID().toString();
                ids.add(docId);
                segments.add(TextSegment.from(text, Metadata.from(Map.of(
                        "userId", String.valueOf(userId), "type", "extracted",
                        "createTime", createTimeMs))));
            }
            try {
                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                embeddingStore.addAll(ids, embeddings, segments);
                for (int i = 0; i < records.size(); i++) {
                    memoryRecordMapper.updateVectorId(records.get(i).getRecordId(), ids.get(i));
                }
                log.info("记忆写入完成：MySQL {}条 + Chroma {}条", records.size(), ids.size());
            } catch (Exception ve) {
                log.error("Chroma写入失败，等待补偿任务补写: {}", ve.getMessage());
            }
            return newItems.size();
        } catch (Exception e) {
            log.error("记忆写入失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 对单条候选记忆做 Chroma top-1 检索，返回与该用户已有提取记忆的最高余弦分数。
     * 检索失败按 0 分处理（宁漏判重不丢记忆）。
     */
    private double top1Score(Embedding queryEmb, Long userId) {
        try {
            Filter filter = Filter.and(
                    MetadataFilterBuilder.metadataKey("userId").isEqualTo(String.valueOf(userId)),
                    MetadataFilterBuilder.metadataKey("type").isEqualTo("extracted"));
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                            EmbeddingSearchRequest.builder()
                                    .queryEmbedding(queryEmb)
                                    .maxResults(1)
                                    .filter(filter)
                                    .build())
                    .matches();
            return matches.isEmpty() ? 0.0 : matches.get(0).score();
        } catch (Exception e) {
            log.warn("向量去重检索失败，该条按非重复处理: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * 补偿任务：为 vector_id 为空的记忆补写向量（Chroma 写入失败的兜底）。
     * 每分钟最多补 50 条，避免瞬时打满嵌入 API。
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void compensateNullVectorMemories() {
        List<MemoryRecord> pendings = memoryRecordMapper.findByNullVectorId(50);
        if (pendings.isEmpty()) {
            return;
        }
        log.info("补偿任务：发现{}条无向量记忆，开始补写", pendings.size());
        List<String> ids = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        List<MemoryRecord> valid = new ArrayList<>();
        for (MemoryRecord r : pendings) {
            if (r.getCreateTime() == null) continue; // 无时间无法构造完整 metadata，跳过待人工处理
            String docId = UUID.randomUUID().toString();
            String createTimeMs = String.valueOf(
                    r.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            ids.add(docId);
            valid.add(r);
            segments.add(TextSegment.from(r.getMemoryText(), Metadata.from(Map.of(
                    "userId", String.valueOf(r.getUserId()), "type", "extracted",
                    "createTime", createTimeMs))));
        }
        if (valid.isEmpty()) {
            return;
        }
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(ids, embeddings, segments);
            for (int i = 0; i < valid.size(); i++) {
                memoryRecordMapper.updateVectorId(valid.get(i).getRecordId(), ids.get(i));
            }
            log.info("补偿任务完成：补写{}条记忆向量", valid.size());
        } catch (Exception e) {
            if (e.getMessage() != null && (e.getMessage().contains("429") || e.getMessage().contains("rate limit"))) {
                log.warn("补偿任务触发嵌入限流，下轮重试");
                return;
            }
            log.error("补偿任务补写失败，下轮重试: {}", e.getMessage());
        }
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
            Embedding queryEmbedding = embeddingModel.embed("学习记忆").content();
            Filter filter = Filter.and(
                    MetadataFilterBuilder.metadataKey("userId").isEqualTo(String.valueOf(userId)),
                    MetadataFilterBuilder.metadataKey("type").isEqualTo("extracted"));
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                            EmbeddingSearchRequest.builder()
                                    .queryEmbedding(queryEmbedding)
                                    .maxResults(200)
                                    .filter(filter)
                                    .build())
                    .matches();
            if (!matches.isEmpty()) {
                List<String> docIds = matches.stream()
                        .map(EmbeddingMatch::embeddingId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
                embeddingStore.removeAll(docIds);
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
                            embeddingStore.removeAll(List.of(r.getVectorId()));
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
