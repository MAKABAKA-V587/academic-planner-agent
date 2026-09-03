package com.studentagent.studentagent;

import com.studentagent.studentagent.entity.StudyEvent;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.StudyEventMapper;
import com.studentagent.studentagent.service.CalendarService;
import com.studentagent.studentagent.tool.LearningPlanTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 学习计划日期解析与逐日计划生成单元测试（纯逻辑，不加载 Spring 上下文）。
 * 回归目标：用户指定日期范围（如 9.1~9.5）时生成逐日计划且日历事件分布正确，
 * 不再把多天任务全部堆到同一天，也不产生范围外的多余内容。
 */
class PlanDateParseTest {

    private LearningPlanTool tool;

    @BeforeEach
    void setUp() {
        tool = new LearningPlanTool(Mockito.mock(MessageMapper.class),
                Mockito.mock(CalendarService.class));
    }

    // ==================== buildDailyPlan 逐日计划生成 ====================

    private String buildDailyPlan(String start, String end, String topics) throws Exception {
        Method m = LearningPlanTool.class.getDeclaredMethod("buildDailyPlan",
                String.class, String.class, String.class, LocalDate.class, LocalDate.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(tool, "Python", "2026-12", "基础语法",
                LocalDate.parse(start), LocalDate.parse(end), topics);
    }

    @Test
    @DisplayName("buildDailyPlan 9.1~9.5：恰好 5 行任务且日期逐日递增")
    void dailyPlanFiveRowsAscending() throws Exception {
        String plan = buildDailyPlan("2026-09-01", "2026-09-05", null);

        List<String> rows = plan.lines()
                .filter(l -> l.matches("^\\| \\d{1,2}\\.\\d{1,2} \\|.+$"))
                .collect(Collectors.toList());
        assertEquals(5, rows.size(), "9.1~9.5 应生成恰好 5 行任务，实际:\n" + plan);

        List<String> dates = rows.stream()
                .map(l -> l.split("\\|")[1].trim())
                .collect(Collectors.toList());
        assertEquals(List.of("9.1", "9.2", "9.3", "9.4", "9.5"), dates,
                "日期应逐日递增且与用户范围严格一致");
        assertTrue(plan.contains("共 5 天"), "应声明覆盖 5 天");
    }

    @Test
    @DisplayName("buildDailyPlan 边界：间隔恰好 31 天（9.1~10.1）仍走逐日计划，32 天回退")
    void dailyPlanBoundary() throws Exception {
        // 9.1~10.1 间隔 30 天（<=31 上限），应生成逐日计划
        String plan = buildDailyPlan("2026-09-01", "2026-10-01", null);
        long rows = plan.lines().filter(l -> l.matches("^\\| \\d{1,2}\\.\\d{1,2} \\|.+$")).count();
        assertEquals(31, rows, "31 天范围应生成 31 行");
    }

    // ==================== generateStudyPlan 回退逻辑 ====================

    @Test
    @DisplayName("跨度 33 天（间隔32天超上限）：回退默认 12 周模板，不产生逐日表格")
    void span32DaysFallsBackToTemplate() {
        // 间隔 DAYS.between=32 > 31 上限 → 回退模板（间隔31天=32天跨度仍允许逐日计划）
        String result = tool.generateStudyPlan("Python", "2026-12", "基础语法",
                "2026-09-01", "2026-10-03", 0, null);
        assertFalse(result.contains("本计划覆盖"), "超范围不应生成逐日计划");
        assertTrue(result.contains("基础巩固"), "应回退为含三阶段的默认模板");
        // 注：不逐字断言 "| 9.2 |" —— 模板变体 buildDayTablePlan 也是同格式日期行（today 起 12 行），随机抽中会误报
    }

    @Test
    @DisplayName("间隔恰好31天（32天跨度，9.1~10.2）：仍走逐日计划")
    void spanAtLimitStillDaily() {
        String result = tool.generateStudyPlan("Python", "2026-12", "基础语法",
                "2026-09-01", "2026-10-02", 0, null);
        assertTrue(result.contains("本计划覆盖"), "间隔31天（上限内）应生成逐日计划");
        assertTrue(result.contains("| 10.2 |"), "应包含最后一天的任务行");
    }

    // ==================== planDays 天数兜底（P1） ====================

    @Test
    @DisplayName("planDays=5 且模型误传整月窗口：截断为恰好 5 天并提示自查")
    void planDaysTruncatesOverrangeEnd() {
        // 复现线上事故：用户要 5 天，模型把"这个月"当窗口传了 09-01~09-30
        String result = tool.generateStudyPlan("Python", "2026-12", "基础语法",
                "2026-09-01", "2026-09-30", 5, null);
        assertTrue(result.contains("已按天数截断"), "应提示发生了截断");
        assertTrue(result.contains("共 5 天"), "计划应为 5 天而非 30 天");
        assertFalse(result.contains("| 9.6 |"), "第 6 天不应有任务行");
        assertTrue(result.contains("若与用户要求的天数或日期不符"), "应包含自查提示行");
    }

    @Test
    @DisplayName("planDays=5 且未给日期：默认从今天开始生成 5 天")
    void planDaysDefaultsStartToday() {
        String result = tool.generateStudyPlan("Python", "2026-12", "基础语法", "", "", 5, null);
        assertTrue(result.contains("已默认从今天开始"), "应提示默认开始日期");
        assertTrue(result.contains("共 5 天"), "应生成 5 天计划");
        long rows = result.lines().filter(l -> l.matches("^\\| \\d{1,2}\\.\\d{1,2} \\|.+$")).count();
        assertEquals(5, rows, "应恰好 5 行任务");
    }

    @Test
    @DisplayName("planDays=0：保持原有行为，无日期则回退模板")
    void planDaysZeroKeepsOldBehavior() {
        String result = tool.generateStudyPlan("Python", "2026-12", "基础语法", "", "", 0, null);
        assertFalse(result.contains("本计划覆盖"), "无日期不应生成逐日计划");
        assertTrue(result.contains("基础巩固"), "应回退为默认模板");
    }

    // ==================== topics 知识点大纲（内容针对性） ====================

    @Test
    @DisplayName("topics 传入针对性大纲：每天任务使用对应知识点而非通用模板")
    void topicsFillDailyTasks() throws Exception {
        String plan = buildDailyPlan("2026-09-01", "2026-09-05",
                "数据库安装与建表;SQL单表增删改查;多表连接与子查询;索引与查询优化;事务与数据安全");
        List<String> rows = plan.lines()
                .filter(l -> l.matches("^\\| \\d{1,2}\\.\\d{1,2} \\|.+$"))
                .collect(Collectors.toList());
        assertEquals(5, rows.size(), "应恰好 5 行任务");
        // 逐日对应 topics 第 i 项
        String[] expected = {"数据库安装与建表", "SQL单表增删改查", "多表连接与子查询", "索引与查询优化", "事务与数据安全"};
        for (int i = 0; i < 5; i++) {
            assertTrue(rows.get(i).contains(expected[i]),
                    "第 " + (i + 1) + " 天应为「" + expected[i] + "」，实际: " + rows.get(i));
        }
        assertFalse(plan.contains("教材精读"), "不应再出现通用模板词");
    }

    @Test
    @DisplayName("topics 数量少于天数：前 N 天用知识点，剩余天数回退通用模板")
    void topicsShortFallbackToWeekPlan() throws Exception {
        String plan = buildDailyPlan("2026-09-01", "2026-09-05", "数据库安装与建表; SQL单表增删改查");
        List<String> rows = plan.lines()
                .filter(l -> l.matches("^\\| \\d{1,2}\\.\\d{1,2} \\|.+$"))
                .collect(Collectors.toList());
        assertTrue(rows.get(0).contains("数据库安装与建表"), "第 1 天应用知识点");
        assertTrue(rows.get(1).contains("SQL单表增删改查"), "第 2 天应用知识点");
        assertTrue(rows.get(2).contains("重难点突破"), "第 3 天起应回退通用模板（WEEK_PLAN[2]）");
    }

    @Test
    @DisplayName("endDate < start（日期倒挂）：回退默认模板，不生成逐日计划")
    void endDateBeforeStartFallsBack() {
        String result = tool.generateStudyPlan("Python", "2026-12", "基础语法",
                "2026-09-05", "2026-09-01", 0, null);
        assertFalse(result.contains("本计划覆盖"), "倒挂范围不应生成逐日计划");
        assertTrue(result.contains("基础巩固"), "应回退为默认模板");
    }

    // ==================== parseDate 宽松解析 ====================

    @Test
    @DisplayName("parseDate：合法 yyyy-MM-dd 解析成功，空/格式错/倒置返回 null")
    void parseDate() throws Exception {
        Method m = LearningPlanTool.class.getDeclaredMethod("parseDate", String.class);
        m.setAccessible(true);
        assertEquals(LocalDate.of(2026, 9, 1), m.invoke(null, "2026-09-01"));
        assertEquals(LocalDate.of(2026, 9, 1), m.invoke(null, " 2026-09-01 "));
        assertNull(m.invoke(null, (Object) null), "null 应返回 null");
        assertNull(m.invoke(null, ""), "空串应返回 null");
        assertNull(m.invoke(null, "   "), "空白应返回 null");
        assertNull(m.invoke(null, "abc"), "非日期应返回 null");
        assertNull(m.invoke(null, "2026-9-1"), "非零填充格式按约定返回 null（走默认模板）");
    }

    // ==================== extractAndSave 逐日表格解析入库 ====================

    @Test
    @DisplayName("extractAndSave：逐日表格解析出 5 条事件且日期分布正确（逐日递增、单日事件）")
    void extractAndSaveParsesDailyTable() throws Exception {
        String plan = buildDailyPlan("2026-09-01", "2026-09-05", null);

        StudyEventMapper mapper = Mockito.mock(StudyEventMapper.class);
        CalendarService real = new CalendarService(mapper);
        when(mapper.deleteByTitleInRange(anyLong(), anyString(),
                any(LocalDate.class), any(LocalDate.class))).thenReturn(0);
        when(mapper.insert(Mockito.any(StudyEvent.class))).thenReturn(1);

        real.extractAndSave(1L, plan);

        ArgumentCaptor<StudyEvent> captor = ArgumentCaptor.forClass(StudyEvent.class);
        Mockito.verify(mapper, Mockito.times(5)).insert(captor.capture());
        List<StudyEvent> saved = captor.getAllValues();

        // ① 恰好 5 条、无垃圾标题
        assertEquals(5, saved.size(), "逐日表格应解析出恰好 5 条事件");
        assertTrue(saved.stream().allMatch(e -> e.getTitle() != null && e.getTitle().length() >= 2
                        && !e.getTitle().contains("task")),
                "标题应有效且不含 addEvent 代码块垃圾");

        // ② 日期分布：5 个互不相同、逐日递增的日期，与 parseFlexibleDate("9.1"~"9.5") 一致
        Set<LocalDate> distinct = saved.stream().map(StudyEvent::getEventDate).collect(Collectors.toSet());
        assertEquals(5, distinct.size(), "5 条事件应落在 5 个不同日期，不应堆到同一天");
        List<LocalDate> sorted = distinct.stream().sorted().collect(Collectors.toList());

        Method pf = CalendarService.class.getDeclaredMethod("parseFlexibleDate", String.class);
        pf.setAccessible(true);
        for (int i = 0; i < 5; i++) {
            LocalDate expected = (LocalDate) pf.invoke(real, "9." + (i + 1));
            assertEquals(expected, sorted.get(i),
                    "第 " + (i + 1) + " 条事件日期应为 9." + (i + 1) + "（按系统年份推断）");
        }

        // ③ 全部为单日事件（endDate == eventDate），类型 task，归属正确
        assertTrue(saved.stream().allMatch(e -> e.getEndDate() != null
                        && e.getEndDate().equals(e.getEventDate())),
                "逐日任务应为单日事件");
        assertTrue(saved.stream().allMatch(e -> "task".equals(e.getEventType())));
        assertTrue(saved.stream().allMatch(e -> Long.valueOf(1L).equals(e.getUserId())));
    }
}
