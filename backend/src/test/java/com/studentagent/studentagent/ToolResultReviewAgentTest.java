package com.studentagent.studentagent;

import com.studentagent.studentagent.service.review.ToolResultReviewAgent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审Agent R4 内容质量检查项单测：
 * generateStudyPlan 未传知识点大纲且结果为通用模板内容时，追加"如实告知通用框架"提示；
 * 传了 topics / 其他工具 / 非模板内容 一律不命中。
 */
class ToolResultReviewAgentTest {

    private ToolResultReviewAgent agent;

    /** 通用模板内容片段（WEEK_PLAN 词） */
    private static final String GENERIC_PLAN =
            "# 📚 Python 学习计划\n\n| 日期 | 任务 | 类型 |\n|------|------|------|\n| 9.1 | 教材精读 + 视频课程 | 学习 |";

    /** 针对性内容片段（无 WEEK_PLAN 词） */
    private static final String SPECIFIC_PLAN =
            "| 9.1 | 数据库安装与基本操作 | 学习 |\n| 9.2 | SQL基础查询语法 | 学习 |";

    @BeforeEach
    void setUp() {
        agent = new ToolResultReviewAgent(Mockito.mock(ChatModel.class));
        ReflectionTestUtils.setField(agent, "enabled", true);
    }

    private ToolExecutionRequest planRequest(String arguments) {
        return ToolExecutionRequest.builder()
                .id("1").name("generateStudyPlan").arguments(arguments).build();
    }

    @Test
    @DisplayName("R4 命中：无 topics 参数 + 通用模板内容 → 追加内容质量提示")
    void r4HitsWithoutTopics() {
        String args = "{\"subject\":\"Python\",\"examTime\":\"2026-12\",\"planDays\":5}";
        String reviewed = agent.review("帮我生成学习计划", planRequest(args), GENERIC_PLAN);
        assertTrue(reviewed.contains("[评审Agent-内容质量]"), "应追加内容质量提示");
        assertTrue(reviewed.contains(GENERIC_PLAN.strip()), "原始计划内容应保留（不拦截）");
    }

    @Test
    @DisplayName("R4 命中：topics 传空字符串视同未传")
    void r4HitsWithEmptyTopics() {
        String args = "{\"subject\":\"Python\",\"topics\":\"\"}";
        String reviewed = agent.review("帮我生成学习计划", planRequest(args), GENERIC_PLAN);
        assertTrue(reviewed.contains("[评审Agent-内容质量]"), "空 topics 应视同未传");
    }

    @Test
    @DisplayName("R4 不命中：模型传了非空 topics → 原样放行")
    void r4SkipsWhenTopicsPresent() {
        String args = "{\"subject\":\"MySQL\",\"topics\":\"数据库安装与建表;SQL单表查询\"}";
        String reviewed = agent.review("帮我生成学习计划", planRequest(args), GENERIC_PLAN);
        assertEquals(GENERIC_PLAN, reviewed, "传了大纲不应追加提示");
    }

    @Test
    @DisplayName("R4 不命中：结果为针对性内容（无通用模板词）→ 原样放行")
    void r4SkipsWhenContentSpecific() {
        String args = "{\"subject\":\"Python\",\"planDays\":5}";
        String reviewed = agent.review("帮我生成学习计划", planRequest(args), SPECIFIC_PLAN);
        assertEquals(SPECIFIC_PLAN, reviewed, "针对性内容不应追加提示");
    }

    @Test
    @DisplayName("R4 不命中：其他工具结果含通用词 → 不检查内容质量")
    void r4SkipsOtherTools() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("2").name("queryEvents")
                .arguments("{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-05\"}").build();
        String reviewed = agent.review("我的计划", req, GENERIC_PLAN);
        assertEquals(GENERIC_PLAN, reviewed, "非计划工具不适用内容质量检查");
    }

    @Test
    @DisplayName("R1 回归：空结果仍被拦截为防幻觉提示")
    void r1EmptyResultStillBlocked() {
        String args = "{\"subject\":\"Python\"}";
        String reviewed = agent.review("帮我生成学习计划", planRequest(args), "  ");
        assertTrue(reviewed.contains("工具返回空结果"), "空结果应走 R1 拦截");
        assertFalse(reviewed.contains("[评审Agent-内容质量]"), "空结果不应走 R4");
    }
}
