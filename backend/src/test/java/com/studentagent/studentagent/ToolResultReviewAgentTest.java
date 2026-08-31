package com.studentagent.studentagent;

import com.studentagent.studentagent.service.review.ToolResultReviewAgent;
import com.studentagent.studentagent.tool.ToolContextHolder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评审 Agent 单元测试：规则评审（空结果/显式错误/跨用户泄露）+ LLM 评审（VALID/INVALID/降级）。
 * 纯 Mockito 单元测试，不加载 Spring 上下文。
 */
class ToolResultReviewAgentTest {

    private ChatModel chatModel;
    private ToolResultReviewAgent agent;

    @BeforeEach
    void setUp() throws Exception {
        chatModel = Mockito.mock(ChatModel.class);
        agent = new ToolResultReviewAgent(chatModel);
        setField("enabled", true);
        setField("llmTimeoutMs", 2000L);
        setField("llmTools", "searchKnowledge,webSearch,queryEvents");
    }

    @AfterEach
    void tearDown() {
        ToolContextHolder.clear();
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ToolResultReviewAgent.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(agent, value);
    }

    private ToolExecutionRequest request(String toolName) {
        return ToolExecutionRequest.builder()
                .id("req-1")
                .name(toolName)
                .arguments("{\"keyword\":\"链表\"}")
                .build();
    }

    private String review(String toolName, String result) {
        return agent.review("帮我查一下链表", request(toolName), result);
    }

    // ========== 开关与直通 ==========

    private void stubLlm(String verdict) {
        when(chatModel.chat(anyList())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(verdict)).build());
    }

    private void stubLlmError() {
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("api down"));
    }

    @Test
    @DisplayName("开关关闭：一切直通（含空结果）")
    void disabledPassThrough() throws Exception {
        setField("enabled", false);
        assertEquals("", review("addEvent", ""));
        verify(chatModel, never()).chat(anyList());
    }

    @Test
    @DisplayName("显式错误文本：直通，模型需要看到错误才能向用户解释")
    void explicitErrorPassThrough() {
        String result = "错误：找不到工具 foo";
        assertEquals(result, review("addEvent", result));
        verify(chatModel, never()).chat(anyList());
    }

    @Test
    @DisplayName("写工具正常结果：直通且不触发 LLM 评审")
    void writeToolPassThrough() {
        String result = "已为用户添加事件：复习高数（2026-09-01）";
        assertEquals(result, review("addEvent", result));
        verify(chatModel, never()).chat(anyList());
    }

    // ========== 规则评审 R1：空结果防幻觉 ==========

    @Test
    @DisplayName("空结果：替换为防幻觉提示")
    void emptyResultIntercepted() {
        String reviewed = review("addEvent", "   ");
        assertTrue(reviewed.contains("不要编造成功信息"));
    }

    // ========== 规则评审 R3：跨用户泄露 ==========

    @Test
    @DisplayName("结果含他人 userId：整条拦截")
    void crossUserLeakIntercepted() {
        ToolContextHolder.set(1L, 11L, false);
        String result = "[{\"title\":\"别人玩家的计划\",\"userId\":99}]";
        String reviewed = review("queryEvents", result);
        assertTrue(reviewed.contains("不属于当前用户"));
    }

    @Test
    @DisplayName("结果含本人 userId：直通")
    void ownUserIdPassThrough() {
        ToolContextHolder.set(1L, 11L, false);
        String result = "[{\"title\":\"我的计划\",\"userId\":11}]";
        assertEquals(result, review("queryEvents", result));
    }

    @Test
    @DisplayName("无登录上下文（userId=null）：跳过跨用户比对，直通")
    void noContextPassThrough() {
        String result = "[{\"title\":\"计划\",\"userId\":99}]";
        assertEquals(result, review("queryEvents", result));
    }

    // ========== LLM 评审（只读工具） ==========

    @Test
    @DisplayName("LLM 评审 VALID：原样放行")
    void llmValidPassThrough() {
        stubLlm("VALID");
        String result = "链表：由节点组成的线性结构，每个节点含数据域和指针域……";
        assertEquals(result, review("searchKnowledge", result));
    }

    @Test
    @DisplayName("LLM 评审 INVALID：替换为不可信提示")
    void llmInvalidReplaced() {
        stubLlm("INVALID:返回内容与用户问题无关");
        String reviewed = review("searchKnowledge", "完全无关的内容");
        assertTrue(reviewed.contains("不可用"));
        assertTrue(reviewed.contains("不要引用该结果"));
    }

    @Test
    @DisplayName("LLM 输出不认识：降级放行原始结果")
    void llmUnknownOutputPassThrough() {
        stubLlm("我觉得还行吧");
        String result = "链表相关内容";
        assertEquals(result, review("searchKnowledge", result));
    }

    @Test
    @DisplayName("LLM 抛异常：降级放行原始结果（永不比现状差）")
    void llmErrorPassThrough() {
        stubLlmError();
        String result = "搜索结果正文";
        assertEquals(result, review("webSearch", result));
    }

    @Test
    @DisplayName("LLM 评审超时：降级放行原始结果")
    void llmTimeoutPassThrough() throws Exception {
        setField("llmTimeoutMs", 0L);
        when(chatModel.chat(anyList())).thenAnswer(inv -> {
            Thread.sleep(50);
            return ChatResponse.builder().aiMessage(AiMessage.from("VALID")).build();
        });
        String result = "搜索结果正文";
        assertEquals(result, review("webSearch", result));
    }

    @Test
    @DisplayName("LLM 评审只对配置的只读工具触发，写工具永不触发")
    void llmOnlyForConfiguredTools() {
        String result = "正常结果";
        assertEquals(result, review("generateStudyPlan", result));
        verify(chatModel, never()).chat(anyList());
    }
}
