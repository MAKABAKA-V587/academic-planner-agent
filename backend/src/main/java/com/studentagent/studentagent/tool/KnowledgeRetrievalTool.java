package com.studentagent.studentagent.tool;

import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.mapper.MessageMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 知识点检索工具 —— 内存关键词匹配优先（毫秒级），向量检索兜底（3秒超时）。
 */
@Slf4j
@Component
public class KnowledgeRetrievalTool {

    /** 科目别名 → 知识库标准科目名（LLM 常传缩写，如 计网/高数/线代） */
    private static final Map<String, String> SUBJECT_ALIASES = Map.ofEntries(
            Map.entry("计网", "计算机网络"),
            Map.entry("网络", "计算机网络"),
            Map.entry("DS", "数据结构"),
            Map.entry("数据结构与算法", "数据结构"),
            Map.entry("OS", "操作系统"),
            Map.entry("os", "操作系统"),
            Map.entry("高数", "高等数学"),
            Map.entry("线代", "线性代数"),
            Map.entry("计组", "计算机组成"),
            Map.entry("组成原理", "计算机组成"),
            Map.entry("计算机组成原理", "计算机组成"),
            Map.entry("概率论与数理统计", "概率论"),
            Map.entry("数理统计", "概率论"),
            Map.entry("C", "C语言"),
            Map.entry("c", "C语言"),
            Map.entry("C++", "C语言"),
            Map.entry("java", "Java"),
            Map.entry("python", "Python"),
            Map.entry("算法", "算法设计"),
            Map.entry("政治", "考研政治"),
            Map.entry("英语", "考研英语"),
            Map.entry("机器学习", "机器学习"),
            Map.entry("深度学习", "机器学习")
    );

    private final MessageMapper messageMapper;
    private final KnowledgeBaseLoader knowledgeBaseLoader;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    public KnowledgeRetrievalTool(MessageMapper messageMapper,
                                   KnowledgeBaseLoader knowledgeBaseLoader,
                                   EmbeddingStore<TextSegment> embeddingStore,
                                   EmbeddingModel embeddingModel) {
        this.messageMapper = messageMapper;
        this.knowledgeBaseLoader = knowledgeBaseLoader;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    @Tool("根据科目和知识点关键词查询专业课程学习指引，返回核心概念、学习重点和常见考点的结构化内容")
    public String searchKnowledge(
            @P("科目名称，如数据结构、操作系统、计算机网络、高等数学、线性代数等") String subject,
            @P("知识点关键词，如链表、死锁、TCP、导数、矩阵等") String keyword) {

        log.info("[工具调用] searchKnowledge: subject={}, keyword={}", subject, keyword);

        // 保存工具调用消息到 DB
        saveToolMessage("tool_call", "searchKnowledge", subject, keyword);

        String result = doSearch(subject, keyword);

        // 保存工具返回结果
        saveToolMessage("tool_result", result, subject, keyword);

        return result;
    }

    private String doSearch(String subject, String keyword) {
        // 1. 内存关键词匹配（毫秒级）优先 —— 避免每次调用都等远程 embedding
        Map<String, Map<String, String>> knowledgeBase = knowledgeBaseLoader.getKnowledgeBase();
        String normalizedSubject = normalizeSubject(subject);

        // 1a. 精确科目内匹配
        if (normalizedSubject != null) {
            Map<String, String> subjectMap = knowledgeBase.get(normalizedSubject);
            if (subjectMap != null) {
                String hit = matchTopic(subjectMap, keyword);
                if (hit != null) {
                    log.info("内存匹配命中: {}-{}", normalizedSubject, hit);
                    return "【" + normalizedSubject + " - " + hit + "】\n" + subjectMap.get(hit)
                            + "\n\n【知识来源】本地知识库「" + normalizedSubject + " - " + hit + "」";
                }
            }
        }

        // 1b. 跨科目模糊匹配
        if (keyword != null && !keyword.isBlank()) {
            for (Map.Entry<String, Map<String, String>> subjectEntry : knowledgeBase.entrySet()) {
                String hit = matchTopic(subjectEntry.getValue(), keyword);
                if (hit != null) {
                    log.info("跨科目内存匹配: {}-{}", subjectEntry.getKey(), hit);
                    return "【" + subjectEntry.getKey() + " - " + hit + "】\n" + subjectEntry.getValue().get(hit)
                            + "\n\n【知识来源】本地知识库「" + subjectEntry.getKey() + " - " + hit + "」";
                }
            }
        }

        // 2. 向量检索兜底（3秒超时，防止远程 embedding 卡住整个工具调用）
        try {
            String query = (normalizedSubject != null ? normalizedSubject : "")
                    + " " + (keyword != null ? keyword : "");
            List<EmbeddingMatch<TextSegment>> results = CompletableFuture.supplyAsync(() -> {
                try {
                    Embedding queryEmbedding = embeddingModel.embed(query.trim()).content();
                    Filter filter = MetadataFilterBuilder.metadataKey("type").isEqualTo("knowledge");
                    return embeddingStore.search(EmbeddingSearchRequest.builder()
                                    .queryEmbedding(queryEmbedding)
                                    .maxResults(3)
                                    .filter(filter)
                                    .build())
                            .matches();
                } catch (Exception e) {
                    log.warn("向量检索失败: {}", e.getMessage());
                    return List.<EmbeddingMatch<TextSegment>>of();
                }
            }).get(3, TimeUnit.SECONDS);

            if (!results.isEmpty() && results.get(0).score() != null && results.get(0).score() >= 0.5) {
                EmbeddingMatch<TextSegment> best = results.get(0);
                TextSegment segment = best.embedded();
                String bestSubject = segment != null ? segment.metadata().getString("subject") : null;
                String bestTopic = segment != null ? segment.metadata().getString("topic") : null;
                String prefix = (bestSubject != null && bestTopic != null)
                        ? "【" + bestSubject + " - " + bestTopic + "】\n"
                        : "";
                log.info("向量检索命中: {} - {}, score={}", bestSubject, bestTopic, best.score());
                String sourceName = (bestSubject != null ? bestSubject : "知识库")
                        + (bestTopic != null ? " - " + bestTopic : "");
                String text = segment != null ? segment.text() : "";
                return prefix + text + "\n\n【知识来源】本地知识库「" + sourceName + "」";
            }
        } catch (Exception e) {
            log.warn("向量检索超时或失败，跳过: {}", e.getMessage());
        }

        // 3. 未匹配到：返回通用建议
        return """
                【未匹配到具体考点】
                
                关于「%s - %s」，本地知识库暂未收录该知识点的详细信息。
                
                通用学习建议：
                1. 先到中国大学MOOC或B站搜索相关课程视频，建立整体认知。
                2. 查阅《%s》经典教材对应章节，精读概念和例题。
                3. 在力扣/牛客网找相关题目练习，巩固理解。
                4. 整理思维导图，梳理该知识点的核心概念和解题套路。
                5. 如需要更详细的资料，建议搜索相关论文或技术博客。
                
                如需其他相关科目或考点的帮助，可以继续向我提问。"""
                .formatted(subject != null ? subject : "未知", keyword != null ? keyword : "未知",
                           getTextbookName(subject));
    }

    /** 科目别名归一化：映射到知识库标准科目名 */
    private String normalizeSubject(String subject) {
        if (subject == null || subject.isBlank()) return null;
        String alias = SUBJECT_ALIASES.get(subject.trim());
        return alias != null ? alias : subject.trim();
    }

    /** 在科目主题表中按关键词匹配主题名 */
    private String matchTopic(Map<String, String> topicMap, String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String kw = keyword.trim();
        // 完整关键词匹配
        String hit = findHit(topicMap, kw);
        if (hit != null) return hit;
        // 按空白/标点拆词后再匹配（如 "TCP 三次握手" → "TCP" + "三次握手"）
        String[] parts = kw.split("[\\s,，。、;；:：()（）/]+");
        for (String part : parts) {
            if (part.length() < 2) continue;
            String h = findHit(topicMap, part);
            if (h != null) return h;
        }
        // 中文长词：尝试 2~4 字滑动窗口（如 "三次握手" 匹配 "TCP协议" 内容）
        if (kw.length() > 2 && containsChinese(kw)) {
            for (int len = Math.min(4, kw.length()); len >= 2; len--) {
                for (int i = 0; i + len <= kw.length(); i++) {
                    String frag = kw.substring(i, i + len);
                    String h = findHit(topicMap, frag);
                    if (h != null) return h;
                }
            }
        }
        return null;
    }

    private String findHit(Map<String, String> topicMap, String kw) {
        for (String topic : topicMap.keySet()) {
            if (topic.contains(kw) || kw.contains(topic)) return topic;
        }
        return null;
    }

    private boolean containsChinese(String s) {
        for (char c : s.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    private String getTextbookName(String subject) {
        if (subject == null) return "相关";
        return switch (subject) {
            case "数据结构" -> "数据结构（严蔚敏版）";
            case "操作系统" -> "计算机操作系统（汤小丹版）";
            case "计算机网络" -> "计算机网络（谢希仁版）";
            case "计算机组成" -> "计算机组成原理（唐朔飞版）";
            case "数据库" -> "数据库系统概论（王珊版）";
            case "高等数学" -> "高等数学（同济版）";
            case "线性代数" -> "线性代数（同济版）";
            case "概率论" -> "概率论与数理统计（浙大版）";
            case "离散数学" -> "离散数学及其应用（Kenneth Rosen）";
            case "编译原理" -> "编译原理（龙书）";
            case "C语言" -> "C程序设计语言（K&R）";
            case "Java" -> "Java核心技术（卷I）";
            case "软件工程" -> "软件工程导论（张海藩版）";
            case "考研政治" -> "考研政治精讲精练（肖秀荣）";
            default -> "相关专业";
        };
    }

    /**
     * 保存工具调用/返回消息到 chat_message 表
     */
    private void saveToolMessage(String role, String content, String subject, String keyword) {
        try {
            Long sessionId = ToolContextHolder.sessionId();
            if (sessionId == null) return;

            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent("[" + role + "] searchKnowledge(subject=" + subject + ", keyword=" + keyword + ")\n" + content);
            msg.setCreateTime(LocalDateTime.now());
            messageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("保存工具消息失败: {}", e.getMessage());
        }
    }
}
