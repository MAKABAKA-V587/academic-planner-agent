package com.studentagent.studentagent.tool;

import com.studentagent.studentagent.entity.StudyEvent;
import com.studentagent.studentagent.service.CalendarService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 日历管理工具 — AI 可通过自然语言操作用户的学习日历。
 */
@Component
@RequiredArgsConstructor
public class CalendarTool {

    private final CalendarService calendarService;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 查询用户在指定时间范围内的事件
     */
    @Tool("查询用户在指定日期范围内的日历事件。用于回答「我下周有什么安排」「8月有什么计划」等问题")
    public String queryEvents(
            @P("开始日期，格式 yyyy-MM-dd") String startDate,
            @P("结束日期，格式 yyyy-MM-dd") String endDate) {

        Long userId = ToolContextHolder.userId();
        if (userId == null) return "无法获取用户信息";

        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        List<StudyEvent> events = calendarService.getEventsByDateRange(userId, start, end);
        if (events.isEmpty()) return "该时间段内没有事件。";

        return events.stream()
                .map(e -> String.format("- %s：%s ~ %s（%s）",
                        e.getTitle(), e.getEventDate(),
                        e.getEndDate() != null ? e.getEndDate() : e.getEventDate(),
                        typeLabel(e.getEventType())))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 添加日历事件
     */
    @Tool("在用户日历中添加一个学习事件/任务。用于「帮我在8月5号安排复习高数」「下周一加上背单词任务」等")
    public String addEvent(
            @P("事件标题，如「复习高等数学」「做线性代数题」") String title,
            @P("开始日期，格式 yyyy-MM-dd，如 2026-08-05") String startDate,
            @P("结束日期（可选），格式 yyyy-MM-dd。如果是单日事件则与开始日期相同") String endDate,
            @P("事件类型：plan(学习计划)、task(任务)、exam(考试)、review(复习/艾宾浩斯复习)，默认 task") String eventType) {

        Long userId = ToolContextHolder.userId();
        if (userId == null) return "无法获取用户信息";

        StudyEvent event = new StudyEvent();
        event.setUserId(userId);
        event.setTitle(title);
        event.setEventDate(LocalDate.parse(startDate));
        event.setEndDate(endDate != null && !endDate.isBlank() ? LocalDate.parse(endDate) : LocalDate.parse(startDate));
        event.setEventType(eventType != null ? eventType : "task");
        event.setDescription("AI 自动创建");
        event.setSource("ai");
        event.setColor("review".equals(eventType) ? "#9B59B6" : "exam".equals(eventType) ? "#F56C6C" : "#409EFF");

        calendarService.addEvent(userId, event);
        return "已成功添加事件：「" + title + "」(" + startDate + ")";
    }

    /**
     * 删除用户日历中的事件（按标题模糊匹配，日期可选）
     */
    @Tool("删除用户日历中的事件。用于「把基础阶段删掉」「把8月5号的高数删掉」「取消所有强化阶段」等。" +
            "title会做模糊匹配，date为空字符串时删除所有匹配标题的事件（跨日期范围也适用）")
    public String deleteEvent(
            @P("要删除的事件标题（模糊匹配），如「基础阶段」「高数」") String title,
            @P("事件日期，格式 yyyy-MM-dd。传空字符串则删除所有匹配标题的事件") String date) {

        Long userId = ToolContextHolder.userId();
        if (userId == null) return "无法获取用户信息";

        int count;
        if (date != null && !date.isBlank()) {
            LocalDate d = LocalDate.parse(date);
            count = calendarService.deleteByTitleAndDate(userId, title, d);
        } else {
            count = calendarService.deleteByTitle(userId, title);
        }
        return count > 0 ? "已删除 " + count + " 个匹配事件" : "未找到匹配的事件";
    }

    /**
     * 仅清空今日事件（不影响其他日期）
     */
    @Tool("仅清空用户今日的所有日历事件。用于「清空今天的日程」「把今天的任务全删掉」「取消今天所有安排」等")
    public String clearToday() {
        Long userId = ToolContextHolder.userId();
        if (userId == null) return "无法获取用户信息";
        int count = calendarService.clearToday(userId);
        return count > 0 ? "已清空今日 " + count + " 个事件" : "今日没有事件";
    }

    /**
     * 清空用户所有日历事件（核弹操作，慎用）
     */
    @Tool("清空用户所有日历事件（全部历史数据，不可恢复）。仅当用户明确说「清空所有日历」「全部删掉」「把整个日历清空」等才调用此方法。" +
            "如果用户说的是「清空今天」「删除今天」「取消今天」等仅限今日的操作，请调用 clearToday 而不是此方法")
    public String clearAll() {
        Long userId = ToolContextHolder.userId();
        if (userId == null) return "无法获取用户信息";
        int count = calendarService.clearAll(userId);
        return "已清空全部 " + count + " 个日历事件";
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "plan" -> "学习计划";
            case "exam" -> "考试";
            case "review" -> "复习";
            default -> "任务";
        };
    }
}
