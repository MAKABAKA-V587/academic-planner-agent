package com.studentagent.studentagent.service;

import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.entity.StudyEvent;
import com.studentagent.studentagent.entity.SysUser;
import com.studentagent.studentagent.entity.WeeklyReport;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.UserMapper;
import com.studentagent.studentagent.mapper.WeeklyReportMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ReportService {

    private final ChatModel chatModel;
    private final MessageMapper messageMapper;
    private final MemoryRecordMapper memoryRecordMapper;
    private final UserMapper userMapper;
    private final WeeklyReportMapper weeklyReportMapper;
    private final CalendarService calendarService;

    public ReportService(@Qualifier("reportChatModel") ChatModel chatModel,
                          MessageMapper messageMapper,
                          MemoryRecordMapper memoryRecordMapper,
                          UserMapper userMapper,
                          WeeklyReportMapper weeklyReportMapper,
                          CalendarService calendarService) {
        this.chatModel = chatModel;
        this.messageMapper = messageMapper;
        this.memoryRecordMapper = memoryRecordMapper;
        this.userMapper = userMapper;
        this.weeklyReportMapper = weeklyReportMapper;
        this.calendarService = calendarService;
    }

    private static final String WEEKLY_REPORT_PROMPT = """
            你是一位经验丰富的学业规划导师，名叫"学小伴"。请根据学生的学习数据，生成一份温暖、有洞察力的周报。
            
            【输出格式】严格按以下结构输出，用 Markdown：
            
            ## 📊 本周学习周报（{日期范围}）
            
            ### 📝 学习概况
            用 3-4 句话总结本周学习情况，用数据说话（对话次数、新增记忆数、涉及科目）。
            如果有画像标签，推断学生的学习阶段和状态，给出阶段性的评价。
            
            ### 🎯 薄弱项分析
            从对话中找出 2-4 个具体知识点或技能的薄弱信号，每个薄弱项用一段自然文字分析：
            - 用加粗 **知识点名称** 开头，后面自然展开
            - 描述在对话中看到的具体信号和担忧
            - 给 1 条具体突破方法（不要泛泛的"多做题"）
            - 每段之间空一行
            - 正确示例：
              **特征值计算** — 你本周提了3次特征值不会求，这在考研线代中是高频考点。可以用3Blue1Brown视频建立几何直觉，再用李永乐线代讲义第4章专项突破。
            - 禁止用「为什么薄弱/掌握程度/影响/建议」这种分点罗列
            
            ### 🔁 复习提醒
            - 结合【已完成的学习任务】和【历史记忆】的时间信息，找出本周已到遗忘复习期的知识点
            - 用艾宾浩斯遗忘曲线思路：学过 1/2/4/7/15 天后的内容最需要巩固
            - 2-3 条，每条格式：**知识点**（已隔X天）+ 一个具体的复习动作
            - 数据不足时写 1-2 条通用复习建议或整节跳过，不要硬凑
            
            ### 💡 下周行动建议
            - 每条一个要点，加粗关键词或数字
            - 4-5 条，每条可执行：有数量、有具体方法、有频次
            - 做不到的不写，不要说"每天做一套真题"
            
            ### 📈 学习观察
            - 1-2 句，结合本周对话点评学习状态
            - 没实际变化就跳过整节，不要硬凑
            
            【注意事项】
            - 始终用"你"称呼学生，保持亲切自然的导师口吻
            - 从对话样本中提取实际讨论过的知识点，不要凭空编造
            - 数据不足时诚实告知，但始终给出一条通用建议
            - 禁止使用"首先其次最后"等僵硬过渡词
            - 禁止输出任何对话数据中没有的科目或知识点
            """;

    /**
     * 获取本周周报（已有则返回缓存，无则返回空）
     */
    public String getWeeklyReport(Long userId) {
        String weekStart = getWeekStartStr();
        WeeklyReport existing = weeklyReportMapper.findByUserAndWeek(userId, weekStart);
        return existing != null ? existing.getContent() : null;
    }

    /**
     * 生成本周周报并保存到数据库
     */
    public String generateWeeklyReport(Long userId) {
        // 计算本周起止（周一~周日）
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));

        String weekStartStr = weekStart.toString();
        String weekEndStr = weekEnd.toString();

        LocalDateTime weekStartTime = weekStart.atStartOfDay();
        LocalDateTime weekEndTime = weekEnd.plusDays(1).atStartOfDay();

        String dateRange = weekStart.format(DateTimeFormatter.ofPattern("M.d")) + " - " +
                weekEnd.format(DateTimeFormatter.ofPattern("M.d"));

        // 本周消息数
        int msgCount = countMessagesThisWeek(userId, weekStartTime, weekEndTime);

        // 本周新增记忆数
        int memCount = countMemoriesThisWeek(userId, weekStartTime, weekEndTime);

        // 用户档案
        SysUser user = userMapper.findById(userId);
        String profile = buildProfileSummary(user);

        // 学习画像标签
        String tags = user != null && user.getUserTags() != null ? user.getUserTags() : "";

        // 本周对话样本
        String conversationSample = getRecentUserMessages(userId, 20);

        // 已完成的学习任务（近10个，供复习提醒分析遗忘周期）
        String completedTasks = buildCompletedTasks(userId);

        // 历史记忆（带相对时间标注，供判断哪些知识点已到复习期）
        String memories = buildMemoriesWithTime(userId);

        // 拼接 prompt
        String userPrompt = String.format("""
                        日期范围：%s
                        本周对话次数（用户消息数）：%d 次
                        本周新增学习记忆：%d 条
                        学习画像标签：%s
                        学业档案：%s
                        
                        已完成的学习任务（含完成时间，供复习提醒参考）：
                        %s
                        
                        历史记忆（含相对时间，供复习提醒参考）：
                        %s
                        
                        本周对话内容摘要：
                        %s
                        """,
                dateRange, msgCount, memCount,
                tags.isEmpty() ? "暂无" : tags,
                profile,
                completedTasks.isEmpty() ? "暂无已完成任务" : completedTasks,
                memories.isEmpty() ? "暂无历史记忆" : memories,
                conversationSample.isEmpty() ? "暂无对话记录" : conversationSample);

        String content = null;
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                content = chatModel.chat(ChatRequest.builder()
                        .messages(List.of(
                                SystemMessage.from(WEEKLY_REPORT_PROMPT),
                                UserMessage.from(userPrompt)))
                        .build())
                        .aiMessage().text();
                if (content != null && !content.isBlank()) {
                    content = content.trim();
                    break;
                }
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : "";
                boolean isRateLimit = errMsg.contains("429") || errMsg.contains("rate limit") || errMsg.contains("50609");
                if (isRateLimit && i < maxRetries - 1) {
                    int waitSec = (i + 1) * 8; // 8s, 16s, 24s
                    log.warn("生成周报触发限流，{}秒后重试({}/{})", waitSec, i + 1, maxRetries);
                    try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ignored) {}
                } else if (i < maxRetries - 1) {
                    int waitSec = (i + 1) * 2;
                    log.warn("生成周报失败(第{}次尝试)，{}秒后重试: {}", i + 1, waitSec, errMsg);
                    try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ignored) {}
                }
            }
        }
        if (content == null || content.isBlank()) {
            log.error("周报生成最终失败（已重试{}次）", maxRetries);
            return "### 生成失败\n\nAI 服务繁忙，请稍后再试。";
        }

        // 保存到数据库
        WeeklyReport existing = weeklyReportMapper.findByUserAndWeek(userId, weekStartStr);
        if (existing != null) {
            weeklyReportMapper.updateContent(existing.getReportId(), content);
        } else {
            WeeklyReport report = new WeeklyReport();
            report.setUserId(userId);
            report.setWeekStart(weekStartStr);
            report.setWeekEnd(weekEndStr);
            report.setContent(content);
            weeklyReportMapper.insert(report);
        }

        return content;
    }

    /**
     * 获取用户所有周报列表（不含内容体，仅摘要）
     */
    public List<Map<String, Object>> getReportList(Long userId) {
        List<WeeklyReport> reports = weeklyReportMapper.findByUserId(userId);
        List<Map<String, Object>> list = new ArrayList<>();
        String currentWeekStart = getWeekStartStr();
        for (WeeklyReport r : reports) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reportId", r.getReportId());
            item.put("weekStart", r.getWeekStart());
            item.put("weekEnd", r.getWeekEnd());
            item.put("isCurrentWeek", currentWeekStart.equals(r.getWeekStart()));
            item.put("createTime", r.getCreateTime() != null ? r.getCreateTime().toString() : null);
            list.add(item);
        }
        return list;
    }

    /**
     * 根据 ID 获取周报内容（校验所有权）
     */
    public String getReportById(Long userId, Long reportId) {
        List<WeeklyReport> reports = weeklyReportMapper.findByUserId(userId);
        return reports.stream()
                .filter(r -> r.getReportId().equals(reportId))
                .map(WeeklyReport::getContent)
                .findFirst()
                .orElse(null);
    }

    private String getWeekStartStr() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }

    private int countMessagesThisWeek(Long userId, LocalDateTime start, LocalDateTime end) {
        // 用 countByDay 统计7天内，手动过滤本周范围
        List<Map<String, Object>> daily = messageMapper.countByDay(userId, 7);
        return daily.stream()
                .filter(m -> {
                    Object dateObj = m.get("date");
                    if (dateObj == null) return false;
                    LocalDate d = dateObj instanceof java.sql.Date
                            ? ((java.sql.Date) dateObj).toLocalDate()
                            : LocalDate.parse(dateObj.toString());
                    return !d.isBefore(start.toLocalDate()) && !d.isAfter(end.toLocalDate());
                })
                .mapToInt(m -> ((Number) m.get("count")).intValue())
                .sum();
    }

    private int countMemoriesThisWeek(Long userId, LocalDateTime start, LocalDateTime end) {
        List<Map<String, Object>> daily = memoryRecordMapper.countByDay(userId, 7);
        return daily.stream()
                .filter(m -> {
                    Object dateObj = m.get("date");
                    if (dateObj == null) return false;
                    LocalDate d = dateObj instanceof java.sql.Date
                            ? ((java.sql.Date) dateObj).toLocalDate()
                            : LocalDate.parse(dateObj.toString());
                    return !d.isBefore(start.toLocalDate()) && !d.isAfter(end.toLocalDate());
                })
                .mapToInt(m -> ((Number) m.get("count")).intValue())
                .sum();
    }

    private String getRecentUserMessages(Long userId, int limit) {
        var messages = messageMapper.findRecentByUserId(userId, limit);
        if (messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var msg : messages) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent();
                if (content.length() > 100) content = content.substring(0, 100) + "...";
                sb.append("- ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    /** 最近完成的学习任务（含完成日期），供周报复习提醒分析遗忘周期 */
    private String buildCompletedTasks(Long userId) {
        try {
            List<StudyEvent> events = calendarService.findCompletedEvents(userId, 10);
            if (events.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (StudyEvent ev : events) {
                String t = ev.getTitle();
                if (t != null && t.length() > 40) t = t.substring(0, 40) + "...";
                sb.append("- ").append(t).append("（完成于 ").append(ev.getEventDate()).append("）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("读取已完成任务失败: {}", e.getMessage());
            return "";
        }
    }

    /** 历史记忆 + 相对时间标注（如"3周前"），供周报判断哪些知识点已到复习期 */
    private String buildMemoriesWithTime(Long userId) {
        try {
            List<MemoryRecord> records = memoryRecordMapper.findByUserId(userId);
            if (records.isEmpty()) return "";
            LocalDateTime now = LocalDateTime.now();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (MemoryRecord r : records) {
                if (r.getMemoryText() == null || r.getMemoryText().isBlank()) continue;
                if (r.getMemoryText().startsWith("【上传资料】")) continue; // 上传资料原文太长，不注入周报
                String text = r.getMemoryText();
                if (text.length() > 60) text = text.substring(0, 60) + "...";
                String timeTag = r.getCreateTime() != null ? relativeTime(r.getCreateTime(), now) : "未知时间";
                sb.append("- ").append(text).append("（").append(timeTag).append("）\n");
                if (++count >= 15) break;
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("读取历史记忆失败: {}", e.getMessage());
            return "";
        }
    }

    /** 相对时间表达（今天/昨天/N天前/N周前/N个月前/N年前） */
    private String relativeTime(LocalDateTime t, LocalDateTime now) {
        long days = java.time.Duration.between(t, now).toDays();
        if (days < 1) return "今天";
        if (days == 1) return "昨天";
        if (days < 7) return days + "天前";
        if (days < 30) return (days / 7) + "周前";
        if (days < 365) return (days / 30) + "个月前";
        return (days / 365) + "年前";
    }

    private String buildProfileSummary(SysUser user) {
        if (user == null) return "暂无";
        StringBuilder sb = new StringBuilder();
        if (user.getMajor() != null && !user.getMajor().isBlank()) {
            sb.append("专业：").append(user.getMajor()).append("；");
        }
        if (user.getGrade() != null && !user.getGrade().isBlank()) {
            sb.append("年级：").append(user.getGrade()).append("；");
        }
        return sb.isEmpty() ? "暂无" : sb.toString();
    }
}
