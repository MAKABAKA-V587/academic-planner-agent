package com.studentagent.studentagent.tool;

import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.entity.StudyEvent;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.StudyEventMapper;
import com.studentagent.studentagent.service.CalendarService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 艾宾浩斯复习排期工具 —— 按遗忘曲线间隔（当天 / 1 / 2 / 4 / 7 / 15 天后）
 * 自动在日历中创建复习任务，对抗遗忘、巩固记忆。
 */
@Component
@Slf4j
public class ReviewPlanTool {

    /** 艾宾浩斯复习间隔（距首次学习的天数）：当天巩固 + 1/2/4/7/15 天 */
    private static final int[] INTERVALS = {0, 1, 2, 4, 7, 15};

    private final MessageMapper messageMapper;
    private final CalendarService calendarService;
    private final StudyEventMapper studyEventMapper;

    public ReviewPlanTool(MessageMapper messageMapper, CalendarService calendarService, StudyEventMapper studyEventMapper) {
        this.messageMapper = messageMapper;
        this.calendarService = calendarService;
        this.studyEventMapper = studyEventMapper;
    }

    @Tool("根据学过的知识点按艾宾浩斯遗忘曲线自动生成复习计划，并按当天/1天后/2天后/4天后/7天后/15天后自动创建日历复习事件")
    public String scheduleReviewPlan(
            @P("科目名称，如高等数学、考研英语、数据结构") String subject,
            @P("本次学过的知识点，用顿号或逗号分隔，如：微分方程、泰勒公式、格林公式") String knowledgePoints) {

        Long sessionId = ToolContextHolder.sessionId();
        Long userId = ToolContextHolder.userId();
        log.info("[工具调用] scheduleReviewPlan: subject={}, knowledgePoints={}", subject, knowledgePoints);

        // 保存工具调用消息
        saveToolMessage("tool_call", subject, knowledgePoints, sessionId);

        String subj = subject != null && !subject.isBlank() ? subject.trim() : "学习内容";
        // 解析知识点：顿号/逗号/分号/空白分隔，过滤空项，每段截断20字，最多取20个
        String[] rawPoints = knowledgePoints == null ? new String[0]
                : knowledgePoints.split("[,，、;；\\s\\n]+");
        List<String> points = new ArrayList<>();
        for (String p : rawPoints) {
            String t = p.trim();
            if (t.length() < 2) continue;
            if (t.length() > 20) t = t.substring(0, 20);
            points.add(t);
            if (points.size() >= 20) break;
        }
        if (points.isEmpty()) {
            points.add("核心知识点");
        }
        String pointsText = String.join("、", points);

        // 生成计划文本 + 逐间隔创建日历复习事件（同日重复安排时跳过，避免刷屏）
        LocalDate today = LocalDate.now();
        String[] labels = {"当天巩固", "1天后", "2天后", "4天后", "7天后", "15天后"};
        // 批次标识：取知识点前8字（如"第一单元"），同科目不同批次标题不同，避免去重误杀
        String batchTag = pointsText.length() > 8 ? pointsText.substring(0, 8) : pointsText;
        StringBuilder plan = new StringBuilder()
                .append("## 🔁 艾宾浩斯复习计划：").append(subj).append("\n\n")
                .append("| 日期 | 复习任务 | 类型 |\n")
                .append("|------|----------|------|\n");

        int created = 0;
        int skipped = 0;
        for (int i = 0; i < INTERVALS.length; i++) {
            LocalDate date = today.plusDays(INTERVALS[i]);
            String title = "复习·" + subj + "·" + batchTag + "（第" + (i + 1) + "次）";
            String desc = "艾宾浩斯遗忘曲线复习（第" + (i + 1) + "次，距首次学习 " + labels[i] + "）：" + pointsText;
            if (desc.length() > 500) desc = desc.substring(0, 497) + "...";

            if (userId != null) {
                int dup = studyEventMapper.countDuplicate(userId, title, date, date);
                if (dup > 0) {
                    skipped++;
                    log.info("跳过已存在的复习事件: title={}, date={}", title, date);
                } else {
                    StudyEvent event = new StudyEvent();
                    event.setUserId(userId);
                    event.setTitle(title);
                    event.setEventDate(date);
                    event.setEndDate(date);
                    event.setEventType("review");
                    event.setSource("ai");
                    event.setDescription(desc);
                    // 复习事件固定紫色，与普通学习任务区分，日历上一眼可辨
                    event.setColor("#9B59B6");
                    calendarService.addEvent(userId, event);
                    created++;
                    log.info("创建复习事件: title={}, date={}", title, date);
                }
            }
            plan.append("| ").append(date).append(" | ").append(title).append(" | 复习 |\n");
        }

        String result = plan.toString();
        if (created > 0) {
            result += "\n> 已为你把复习任务加入日历（共" + created + "个新任务），勾选完成即可打卡。";
        } else {
            result += "\n> 这些复习任务已在日历中，无需重复添加。";
        }
        result += "\n> 本次纳入复习的知识点（" + points.size() + "个）：" + pointsText;

        // 保存工具返回结果
        saveToolMessage("tool_result", result, subj, sessionId);

        return result;
    }

    /** 保存工具调用/返回消息到 chat_message 表 */
    private void saveToolMessage(String role, String subject, String content, Long sessionId) {
        try {
            if (sessionId == null) return;
            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent("[" + role + "] scheduleReviewPlan(subject=" + subject + ")\n" + content);
            msg.setCreateTime(LocalDateTime.now());
            messageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("保存工具消息失败: {}", e.getMessage());
        }
    }
}
