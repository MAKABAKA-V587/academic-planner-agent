package com.studentagent.studentagent.tool;

import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.service.CalendarService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 学习计划生成工具 —— 基于模板 + 参数拼接，秒级响应。
 * 生成计划后自动提取日期段创建日历事件。
 */
@Component
@Slf4j
public class LearningPlanTool {

    private final MessageMapper messageMapper;
    private final CalendarService calendarService;

    public LearningPlanTool(MessageMapper messageMapper, CalendarService calendarService) {
        this.messageMapper = messageMapper;
        this.calendarService = calendarService;
    }

    /** 12 周的学习内容与目标（供各模板变体复用） */
    private static final String[][] WEEK_PLAN = {
            {"教材精读 + 视频课程", "全面了解知识框架"},
            {"章节习题 + 笔记整理", "掌握基本概念与定理"},
            {"重难点突破 + 专题训练", "攻克核心难点"},
            {"综合练习 + 错题整理", "建立知识体系"},
            {"真题训练 + 考点归纳", "熟悉题型和命题规律"},
            {"专项突破 + 模拟测试", "查漏补缺，提升速度"},
            {"真题套卷 + 限时训练", "适应考试节奏"},
            {"错题复盘 + 考点串联", "建立知识网络"},
            {"全真模拟 + 限时训练", "适应真实考试节奏"},
            {"高频考点 + 专项突破", "强化薄弱环节"},
            {"回归基础 + 错题回顾", "巩固基础，调整心态"},
            {"考前梳理 + 易错回顾", "查漏补缺，轻装上阵"}
    };

    /** 三个阶段覆盖的周范围（第1-4周 / 第5-8周 / 第9-12周） */
    private static final int[][] PHASE_WEEKS = {{1, 4}, {5, 8}, {9, 12}};

    /** 三个阶段的名称 */
    private static final String[] PHASE_NAMES = {"基础巩固", "强化提升", "冲刺实战"};

    /** 模板格式变体数：随机切换避免每次回复千篇一律 */
    private static final int TEMPLATE_VARIANTS = 5;

    @Tool(description = "根据科目、考试时间和薄弱知识点，生成包含基础阶段、强化阶段和冲刺阶段的结构化学习计划")
    public String generateStudyPlan(
            @ToolParam(description = "科目名称，如高等数学、数据结构、考研英语等") String subject,
            @ToolParam(description = "考试时间，格式 yyyy-MM，如 2025-12") String examTime,
            @ToolParam(description = "薄弱知识点描述，如线性代数、二叉树、阅读理解的掌握程度较弱") String weakPoints,
            ToolContext toolContext) {

        log.info("[工具调用] generateStudyPlan: subject={}, examTime={}, weakPoints={}", subject, examTime, weakPoints);

        // 保存工具调用消息
        saveToolMessage("tool_call", "generateStudyPlan", subject, examTime, weakPoints, toolContext);

        String result;
        // 优先模板（秒回），LLM 兜底（仅模板生成失败时使用）
        try {
            result = generateByTemplate(subject, examTime, weakPoints);
        } catch (Exception e) {
            log.warn("模板生成学习计划失败: {}", e.getMessage());
            result = "（学习计划生成异常，请重试）";
        }

        // 保存工具返回结果
        saveToolMessage("tool_result", result, subject, examTime, weakPoints, toolContext);

        // 自动从计划中提取日期段，创建日历事件
        autoExtractCalendarEvents(result, toolContext);

        return result;
    }

    /**
     * 模板填参数拼学习计划（秒级响应）。
     * 随机在几种"日历正则可直接识别"的格式间切换，避免每次回复千篇一律。
     */
    private String generateByTemplate(String subject, String examTime, String weakPoints) {
        String subj = subject != null && !subject.isBlank() ? subject : "课程";
        String time = examTime != null && !examTime.isBlank() ? examTime : "待定";
        String weak = weakPoints != null && !weakPoints.isBlank() ? weakPoints : "暂无特别薄弱的知识点";

        // 随机选用一种格式，日期均从今天实时计算，保证导入正则可直接命中
        LocalDate today = LocalDate.now();
        String plan = switch (ThreadLocalRandom.current().nextInt(TEMPLATE_VARIANTS)) {
            case 0 -> buildRangeTablePlan(subj, time, weak, today);    // | 日期范围 | 内容 | 目标 | 表格
            case 1 -> buildDayTablePlan(subj, time, weak, today);      // | 日期 | 任务 | 类型 | 表格
            case 2 -> buildDayOffsetTablePlan(subj, time, weak, today); // | 1-7 | 内容 | 目标 | 相对天数表格
            case 3 -> buildCnDatePlan(subj, time, weak, today);        // 8月5日：任务 中文日期行
            default -> buildWeekOffsetPlan(subj, time, weak, today);   // 阶段X：名称（X-Y周）+ 子任务列表
        };

        // 尝试从考试时间推算出剩余时间以调整建议
        try {
            YearMonth exam = YearMonth.parse(time);
            YearMonth now = YearMonth.now();
            long monthsLeft = exam.getMonthValue() - now.getMonthValue()
                    + (exam.getYear() - now.getYear()) * 12;
            if (monthsLeft < 0) {
                plan = "> ⚠️ 考试时间 " + time + " 已过，以下为通用学习方案仅供参考。\n\n" + plan;
            } else if (monthsLeft <= 4) {
                plan = "> ⏰ 距离考试还有约 " + monthsLeft + " 个月，建议压缩基础阶段，重点投入强化和冲刺。\n\n" + plan;
            }
        } catch (Exception e) {
            log.warn("考试时间格式解析失败，使用默认模板: {}", e.getMessage());
        }
        return plan;
    }

    /** 格式A：日期范围表格 — | M.d-M.d | 内容 | 目标 |（正则p4匹配，逐周生成任务） */
    private String buildRangeTablePlan(String subj, String time, String weak, LocalDate today) {
        StringBuilder sb = header(subj, time, weak);
        for (int p = 0; p < PHASE_WEEKS.length; p++) {
            int w1 = PHASE_WEEKS[p][0], w2 = PHASE_WEEKS[p][1];
            sb.append("## ").append(PHASE_NAMES[p]).append("阶段（").append(weekRange(today, w1, w2)).append("）\n")
              .append("| 日期 | 学习内容 | 学习目标 |\n")
              .append("|------|----------|----------|\n");
            for (int w = w1 - 1; w <= w2 - 1; w++) {
                sb.append("| ").append(weekRange(today, w + 1, w + 1)).append(" | ")
                  .append(WEEK_PLAN[w][0]).append(" | ").append(WEEK_PLAN[w][1]).append(" |\n");
            }
            sb.append("\n");
        }
        return tail(sb, weak).toString();
    }

    /** 格式B：单日表格 — | M.d | 任务 | 类型 |（正则p4b匹配，每周一行） */
    private String buildDayTablePlan(String subj, String time, String weak, LocalDate today) {
        StringBuilder sb = header(subj, time, weak);
        for (int p = 0; p < PHASE_WEEKS.length; p++) {
            int w1 = PHASE_WEEKS[p][0], w2 = PHASE_WEEKS[p][1];
            sb.append("## ").append(PHASE_NAMES[p]).append("阶段（第").append(w1).append("-").append(w2).append("周）\n")
              .append("| 日期 | 任务 | 类型 |\n")
              .append("|------|------|------|\n");
            for (int w = w1 - 1; w <= w2 - 1; w++) {
                sb.append("| ").append(md(today.plusDays(w * 7L))).append(" | ")
                  .append(WEEK_PLAN[w][0]).append(" | 学习 |\n");
            }
            sb.append("\n");
        }
        return tail(sb, weak).toString();
    }

    /** 格式C：周偏移阶段 + 子任务列表 — 阶段X：名称（X-Y周）+ "- 任务"（正则p12/p13匹配，阶段+子任务） */
    private String buildWeekOffsetPlan(String subj, String time, String weak, LocalDate today) {
        StringBuilder sb = header(subj, time, weak);
        for (int p = 0; p < PHASE_WEEKS.length; p++) {
            int w1 = PHASE_WEEKS[p][0], w2 = PHASE_WEEKS[p][1];
            sb.append("## 阶段").append(p + 1).append("：").append(PHASE_NAMES[p])
              .append("（").append(w1).append("-").append(w2).append("周）\n");
            for (int w = w1 - 1; w <= w2 - 1; w++) {
                sb.append("- ").append(WEEK_PLAN[w][0]).append("（").append(WEEK_PLAN[w][1]).append("）\n");
            }
            sb.append("\n");
        }
        return tail(sb, weak).toString();
    }

    /** 格式D：相对天数表格 — | 1-7 | 内容 | 目标 |（正则p10匹配，1=今天；阶段标题用 p11 第X-Y天） */
    private String buildDayOffsetTablePlan(String subj, String time, String weak, LocalDate today) {
        StringBuilder sb = header(subj, time, weak);
        for (int p = 0; p < PHASE_WEEKS.length; p++) {
            int w1 = PHASE_WEEKS[p][0], w2 = PHASE_WEEKS[p][1];
            int d1 = (w1 - 1) * 7 + 1, d2 = w2 * 7;   // 如 第1-28天 / 第29-56天 / 第57-84天
            sb.append("## ").append(PHASE_NAMES[p]).append("阶段（第").append(d1).append("-").append(d2).append("天）\n")
              .append("| 天数 | 学习内容 | 学习目标 |\n")
              .append("|------|----------|----------|\n");
            for (int w = w1 - 1; w <= w2 - 1; w++) {
                int day1 = w * 7 + 1, day2 = (w + 1) * 7;
                // p10 标题列不支持空格/加号，用"与"连接
                sb.append("| ").append(day1).append("-").append(day2).append(" | ")
                  .append(WEEK_PLAN[w][0].replace(" + ", "与")).append(" | ")
                  .append(WEEK_PLAN[w][1]).append(" |\n");
            }
            sb.append("\n");
        }
        return tail(sb, weak).toString();
    }

    /** 格式E：中文日期行 — 8月5日：任务（正则p6匹配，每周一行） */
    private String buildCnDatePlan(String subj, String time, String weak, LocalDate today) {
        StringBuilder sb = header(subj, time, weak);
        for (int p = 0; p < PHASE_WEEKS.length; p++) {
            int w1 = PHASE_WEEKS[p][0], w2 = PHASE_WEEKS[p][1];
            sb.append("## ").append(PHASE_NAMES[p]).append("阶段（第").append(w1).append("-").append(w2).append("周）\n");
            for (int w = w1 - 1; w <= w2 - 1; w++) {
                LocalDate d = today.plusDays(w * 7L);
                sb.append(d.getMonthValue()).append("月").append(d.getDayOfMonth()).append("日：")
                  .append(WEEK_PLAN[w][0]).append("\n");
            }
            sb.append("\n");
        }
        return tail(sb, weak).toString();
    }

    /** 计划头部：标题 + 考试时间/薄弱点 */
    private StringBuilder header(String subj, String time, String weak) {
        return new StringBuilder()
                .append("# 📚 ").append(subj).append(" 学习计划\n")
                .append("> 考试时间：").append(time).append(" | 薄弱点：").append(weak).append("\n\n");
    }

    /** 计划尾部：薄弱点专项突破 + 备考建议（不会被导入为正则事件） */
    private StringBuilder tail(StringBuilder sb, String weak) {
        return sb.append("## 四、薄弱点专项突破\n")
                .append("> 针对：").append(weak).append("\n\n")
                .append("1. 每天额外安排 30-60 分钟专项练习薄弱知识点\n")
                .append("2. 整理薄弱知识点的思维导图，建立知识联系\n")
                .append("3. 找 3-5 道典型例题反复练习，直到能独立讲出解题思路\n")
                .append("4. 每周做一次薄弱点的阶段性测试，检验提升效果\n\n")
                .append("## 五、备考建议\n")
                .append("- 每周日做周总结，调整下周计划\n")
                .append("- 保持规律作息，每周至少休息半天\n")
                .append("- 与同学组队互相监督、讨论难题\n")
                .append("- 考前一周重点回顾高频考点和易错题\n");
    }

    /** M.d 日期格式（与导入正则兼容） */
    private static String md(LocalDate d) {
        return d.getMonthValue() + "." + d.getDayOfMonth();
    }

    /** 第w1周-第w2周（从今天起算）的日期范围，如 8.5-8.11 */
    private static String weekRange(LocalDate today, int w1, int w2) {
        return md(today.plusDays((w1 - 1) * 7L)) + "-" + md(today.plusDays(w2 * 7L - 1));
    }

    /**
     * 保存工具调用/返回消息到 chat_message 表
     */
    private void saveToolMessage(String role, String content, String subject, String examTime, String weakPoints,
                                 ToolContext toolContext) {
        try {
            Long sessionId = ToolContextHolder.sessionId(toolContext);
            if (sessionId == null) return;

            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent("[" + role + "] generateStudyPlan(subject=" + subject
                    + ", examTime=" + examTime + ", weakPoints=" + weakPoints + ")\n" + content);
            msg.setCreateTime(LocalDateTime.now());
            messageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("保存工具消息失败: {}", e.getMessage());
        }
    }

    /**
     * 从学习计划文本中自动提取日期段，创建日历事件。
     * 失败不影响主流程。
     */
    private void autoExtractCalendarEvents(String planText, ToolContext toolContext) {
        try {
            Long userId = ToolContextHolder.userId(toolContext);
            if (userId == null) {
                log.warn("无法获取用户上下文，跳过日历自动提取");
                return;
            }
            int count = calendarService.extractAndSave(userId, planText);
            log.info("[学习计划工具] 自动提取日历事件 {} 条", count);
        } catch (Exception e) {
            log.warn("[学习计划工具] 自动提取日历事件失败: {}", e.getMessage());
        }
    }
}
