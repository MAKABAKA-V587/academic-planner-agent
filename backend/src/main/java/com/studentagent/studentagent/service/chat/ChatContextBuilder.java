package com.studentagent.studentagent.service.chat;

import com.studentagent.studentagent.entity.StudentProfile;
import com.studentagent.studentagent.entity.StudyMaterial;
import com.studentagent.studentagent.entity.SysUser;
import com.studentagent.studentagent.mapper.ChatSessionMaterialMapper;
import com.studentagent.studentagent.mapper.ProfileMapper;
import com.studentagent.studentagent.mapper.StudyMaterialMapper;
import com.studentagent.studentagent.mapper.UserMapper;
import com.studentagent.studentagent.service.CalendarService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统提示词构建器：日期参考 + 用户上下文注入（摘要/昵称/日历/完成统计/长时记忆召回/会话资料）。
 * 拆分自 ChatService，阻塞端与流式端共用，保证两个入口对 LLM 的上下文一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatContextBuilder {

    private final CalendarService calendarService;
    private final UserMapper userMapper;
    private final ProfileMapper profileMapper;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatSessionMaterialMapper chatSessionMaterialMapper;
    private final StudyMaterialMapper studyMaterialMapper;
    private final ChatHistoryStore historyStore;

    private static final double RECALL_THRESHOLD = 0.7;
    private static final int RECALL_TOP_K = 5;
    private static final int RECALL_FINAL_K = 3;

    /**
     * 构建系统提示词，含当前日期 + 长时记忆召回
     */
    public String buildSystemPrompt(Long userId, Long sessionId, String userMessage) {
        String dateRef = buildDateReference();
        String datedPrompt = ChatPrompts.TOOL_SYSTEM_PROMPT + "\n\n" + dateRef;

        if (userId == null) {
            return datedPrompt;
        }

        // 今日日历 + 长时记忆/档案，与流式端共用同一套上下文
        return datedPrompt + buildUserContext(userId, sessionId, userMessage);
    }

    /**
     * 生成日期参考表，帮 LLM 正确映射"下周一"等相对日期
     */
    public String buildDateReference() {
        LocalDate today = LocalDate.now();
        DayOfWeek dow = today.getDayOfWeek();
        String[] weekDays = {"周一","周二","周三","周四","周五","周六","周日"};

        // 本周一
        LocalDate thisMonday = today.minusDays(dow.getValue() - DayOfWeek.MONDAY.getValue());
        // 下周一
        LocalDate nextMonday = thisMonday.plusDays(7);

        StringBuilder sb = new StringBuilder();
        sb.append("【日期参考】今天是 ").append(today).append("（").append(weekDays[dow.getValue()-1]).append("）。\n");
        sb.append("常用相对日期对应如下（直接用，不要自己算）：\n");

        // 明天/后天
        sb.append(String.format("  - 明天 = %s / 后天 = %s\n",
                today.plusDays(1), today.plusDays(2)));

        // 下周一到周日
        for (int i = 0; i < 7; i++) {
            LocalDate d = nextMonday.plusDays(i);
            sb.append(String.format("  - 下%s = %s\n", weekDays[i], d));
        }

        // 本周剩余 + 下周
        sb.append(String.format("  - 本周剩余 = %s 至 %s\n", today, thisMonday.plusDays(6)));
        sb.append(String.format("  - 下周 = %s 至 %s\n", nextMonday, nextMonday.plusDays(6)));

        return sb.toString();
    }

    /**
     * 流式专用提示词：支持日历工具调用，预注入日历数据
     */
    public String buildStreamSystemPrompt(Long userId, Long sessionId, String userMessage, boolean webSearch) {
        String dateRef = buildDateReference();

        String prompt = ChatPrompts.STREAM_SYSTEM_PROMPT + "\n\n" + dateRef;

        if (userId != null) {
            // 今日日历 + 长时记忆/档案，与阻塞端共用同一套上下文
            prompt += buildUserContext(userId, sessionId, userMessage);
        }

        if (webSearch) {
            prompt += ChatPrompts.WEBSEARCH_HINT;
        }

        return prompt;
    }

    /**
     * SIMPLE 路由精简提示词：角色规范 + 日期参考 + 用户上下文（昵称/日历/记忆召回保留，
     * 个性化是核心卖点；省掉的是工具说明书和工具铁律段）。
     */
    public String buildSimpleSystemPrompt(Long userId, Long sessionId, String userMessage) {
        String prompt = ChatPrompts.SIMPLE_SYSTEM_PROMPT + "\n\n" + buildDateReference();
        if (userId != null) {
            prompt += buildUserContext(userId, sessionId, userMessage);
        }
        return prompt;
    }

    /**
     * 构建用户上下文：今日日历 + 长时记忆召回（或档案兜底）。
     * 阻塞端与流式端共用，保证两个入口对 LLM 的上下文一致。
     */
    private String buildUserContext(Long userId, Long sessionId, String userMessage) {
        StringBuilder sb = new StringBuilder();

        // 方案A：旧轮次滚动摘要（窗口外对话的压缩记忆，保证超出窗口的事实仍可被引用）
        if (sessionId != null) {
            try {
                ChatHistoryStore.SummaryState summary = historyStore.loadSummaryState(sessionId);
                if (summary != null && !summary.text().isEmpty()) {
                    sb.append("\n\n【此前对话摘要】（这些是更早轮次的讨论要点；")
                      .append("与用户当前表述冲突时以当前说法为准）：\n")
                      .append(summary.text());
                }
            } catch (Exception e) {
                log.warn("注入对话摘要失败: {}", e.getMessage());
            }
        }

        // 用户昵称：AI 用昵称称呼用户（个人设置中可修改，以 sys_user.name 为权威来源）
        String userNickname = null;
        try {
            SysUser user = userMapper.findById(userId);
            if (user != null && user.getName() != null && !user.getName().isBlank()) {
                userNickname = user.getName();
                sb.append("\n\n【用户昵称】").append(userNickname)
                  .append("。对话中请用「").append(userNickname)
                  .append("」适当称呼该用户（每段回答开头称呼一次即可，不要过度重复）；"
                          + "若用户问\"我叫什么/我的名字/你认识我吗\"，直接回答该昵称。");
            }
        } catch (Exception e) {
            log.warn("注入用户昵称失败: {}", e.getMessage());
        }

        // 预查今日日历
        try {
            var todayEvents = calendarService.getEventsByDateRange(userId, java.time.LocalDate.now(), java.time.LocalDate.now());
            if (!todayEvents.isEmpty()) {
                sb.append("\n\n【今日日历】\n");
                for (var ev : todayEvents) {
                    sb.append("- ").append(ev.getTitle())
                      .append("（").append(ev.getEventType() != null ? ev.getEventType() : "task").append("）\n");
                }
            }
        } catch (Exception e) {
            log.warn("注入今日日历失败: {}", e.getMessage());
        }

        // 已完成的学习任务：直接从数据源注入（不依赖向量召回，AI 一定知道用户完成过什么；
        // 取消勾选后立即从列表移除，与记忆同步）
        try {
            var completedEvents = calendarService.findCompletedEvents(userId, 10);
            if (!completedEvents.isEmpty()) {
                sb.append("\n\n【已完成的学习任务】\n");
                for (var ev : completedEvents) {
                    sb.append("- ").append(ev.getTitle())
                      .append("（完成于 ").append(ev.getEventDate()).append("）\n");
                }
            }
        } catch (Exception e) {
            log.warn("注入已完成任务失败: {}", e.getMessage());
        }

        // 学习完成统计：给 AI 量化视角，便于分析学习情况
        try {
            Map<String, Integer> stats = calendarService.completionStats(userId);
            int total = stats.getOrDefault("totalCompleted", 0);
            int week = stats.getOrDefault("weekCompleted", 0);
            int pending = stats.getOrDefault("pending", 0);
            if (total > 0 || pending > 0) {
                sb.append("\n\n【学习完成统计】累计完成 ").append(total)
                  .append(" 个学习任务；近7天完成 ").append(week)
                  .append(" 个；当前未完成 ").append(pending)
                  .append(" 个。\n（注：勾选完成仅代表任务已做，不代表已掌握；"
                          + "掌握程度以用户对话中的自我评价为准。）");
            }
        } catch (Exception e) {
            log.warn("注入完成统计失败: {}", e.getMessage());
        }

        // 长时记忆召回（向量检索 → 过滤 → 类别多样性重排 → metadata 时间标注，全程无额外 DB 查询）
        List<RecalledMemory> recalled = recallMemories(userId, userMessage);
        // 昵称以 sys_user.name 为准，过滤记忆中的旧昵称条目，避免与新昵称冲突
        if (userNickname != null) {
            recalled.removeIf(m -> m.text().startsWith("用户昵称-"));
        }
        // 过滤"已完成任务"记忆：完成情况已由【已完成的学习任务】实时注入，避免重复
        recalled.removeIf(m -> m.text().contains("已完成"));
        List<RecalledMemory> pickedMemories = diversifyMemories(recalled);
        if (!pickedMemories.isEmpty()) {
            String tagged = pickedMemories.stream()
                    .map(m -> m.text() + (m.createTimeMs() != null
                            ? "（" + relativeTime(m.createTimeMs()) + "）" : ""))
                    .collect(Collectors.joining("\n- ", "- ", ""));
            sb.append("\n\n【用户历史学习特征】\n").append(tagged);
        } else {
            // 兜底：使用学业档案作为基础画像
            String profileFallback = buildProfileFallback(userId);
            if (!profileFallback.isEmpty()) {
                sb.append("\n\n【用户学习档案】\n").append(profileFallback);
            }
        }

        // 本会话启用的参考资料（用户从资料库选择 / 临时上传挂到会话的）：资料参考以会话为准，
        // 只有挂载到当前会话的资料才会注入，移除会话关联后 AI 不再参考
        if (sessionId != null) {
            try {
                var rels = chatSessionMaterialMapper.findBySessionId(sessionId);
                if (!rels.isEmpty()) {
                    StringBuilder matSb = new StringBuilder();
                    int count = 0;
                    for (var rel : rels) {
                        StudyMaterial m = studyMaterialMapper.findById(rel.getMaterialId());
                        if (m == null || !m.getUserId().equals(userId) || m.getContentText() == null) continue;
                        if (count >= 3) break; // 每会话最多注入 3 份
                        String content = m.getContentText().trim();
                        if (content.isEmpty()) continue;
                        matSb.append("【资料").append(count + 1).append("：").append(m.getFileName()).append("】\n")
                             .append(content.length() > 800 ? content.substring(0, 800) + "…（后略）" : content)
                             .append("\n");
                        count++;
                    }
                    if (count > 0) {
                        sb.append("\n\n【本会话参考资料】（用户明确选择在当前会话中参考的资料，涉及相关内容时必须优先于其他记忆参考）\n")
                          .append(matSb);
                    }
                }
            } catch (Exception e) {
                log.warn("注入会话参考资料失败: {}", e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * 从 Chroma 向量库召回相关记忆（带分数与写入时间 metadata，供多样性重排与时效标注）
     */
    private List<RecalledMemory> recallMemories(Long userId, String userMessage) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(userMessage).content();
            Filter filter = MetadataFilterBuilder.metadataKey("userId")
                    .isEqualTo(String.valueOf(userId));
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                            EmbeddingSearchRequest.builder()
                                    .queryEmbedding(queryEmbedding)
                                    .maxResults(RECALL_TOP_K)
                                    .minScore(RECALL_THRESHOLD)
                                    .filter(filter)
                                    .build())
                    .matches();
            List<RecalledMemory> result = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : matches) {
                if (m.embedded() == null) continue;
                result.add(new RecalledMemory(m.embedded().text(), m.score(), parseCreateTime(m.embedded())));
            }
            return result;
        } catch (Exception e) {
            log.warn("向量库召回失败，降级档案兜底: {}", e.getMessage());
            // 注意：必须返回可变列表，调用方会对结果做 removeIf 过滤
            return new ArrayList<>();
        }
    }

    /** 从向量 metadata 解析写入时间（毫秒时间戳）；存量旧记忆无此字段返回 null（不标注时效） */
    private Long parseCreateTime(TextSegment segment) {
        try {
            String ct = segment.metadata().getString("createTime");
            return ct != null ? Long.parseLong(ct) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 召回多样性重排：按「类别-科目」分组，每组只保留最高分一条，
     * 避免 topK 全被同一科目占满；最终按分数取前 RECALL_FINAL_K 条注入。
     */
    private List<RecalledMemory> diversifyMemories(List<RecalledMemory> matches) {
        if (matches.size() <= RECALL_FINAL_K) {
            return matches;
        }
        // Chroma 返回已按分数降序，putIfAbsent 即保留每组最高分
        Map<String, RecalledMemory> bestPerGroup = new LinkedHashMap<>();
        for (RecalledMemory m : matches) {
            bestPerGroup.putIfAbsent(groupKey(m.text()), m);
        }
        return bestPerGroup.values().stream()
                .sorted(Comparator.comparingDouble(RecalledMemory::score).reversed())
                .limit(RECALL_FINAL_K)
                .collect(Collectors.toList());
    }

    /** 记忆文本的「类别-科目」分组键（格式 类别-科目-描述；解析失败用全文兜底） */
    private String groupKey(String text) {
        String[] parts = text.split("-", 3);
        return parts.length >= 2 ? parts[0].trim() + "-" + parts[1].trim() : text;
    }

    /** 召回的记忆条目：文本 + 相似度分数 + 写入时间（metadata，用于时效标注） */
    private record RecalledMemory(String text, double score, Long createTimeMs) {}

    /** 相对时间表达（如"3周前"），配合记忆时效性规则让 AI 对久远记忆降权 */
    private String relativeTime(Long epochMs) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        long days = Duration.between(t, LocalDateTime.now()).toDays();
        if (days < 1) return "今天";
        if (days == 1) return "昨天";
        if (days < 7) return days + "天前";
        if (days < 30) return (days / 7) + "周前";
        if (days < 365) return (days / 30) + "个月前";
        return (days / 365) + "年前";
    }

    /**
     * 档案兜底：将用户学业档案格式化为基础画像
     */
    private String buildProfileFallback(Long userId) {
        try {
            StudentProfile profile = profileMapper.findByUserId(userId);
            if (profile == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (profile.getWeakSubjects() != null && !profile.getWeakSubjects().isBlank()) {
                sb.append("薄弱科目: ").append(profile.getWeakSubjects()).append("; ");
            }
            if (profile.getExamPlans() != null && !profile.getExamPlans().isBlank()) {
                sb.append("考试计划: ").append(profile.getExamPlans()).append("; ");
            }
            if (profile.getStudyGoals() != null && !profile.getStudyGoals().isBlank()) {
                sb.append("学习目标: ").append(profile.getStudyGoals()).append("; ");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("读取档案兜底失败: {}", e.getMessage());
            return "";
        }
    }
}
