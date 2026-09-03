package com.studentagent.studentagent;

import com.studentagent.studentagent.service.router.ChatRoute;
import com.studentagent.studentagent.service.router.ChatRouter;
import com.studentagent.studentagent.service.router.LlmRouter;
import com.studentagent.studentagent.service.router.RouteDecision;
import com.studentagent.studentagent.service.router.RuleBasedRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 路由门面（ChatRouter）边界用例：开关回滚、规则直命中、LLM 三分支（TASK/CHAT/失败）
 * 与降级链（超时/异常/垃圾输出 → 默认 TOOL 安全超集）。
 * 纯 Mockito 单元测试，不加载 Spring 上下文。
 */
class ChatRouterTest {

    private RuleBasedRouter ruleRouter;
    private LlmRouter llmRouter;
    private ChatRouter router;

    private static final List<String> TURNS = List.of("用户：线性代数怎么学", "AI：建议从行列式入手");

    @BeforeEach
    void setUp() {
        ruleRouter = Mockito.mock(RuleBasedRouter.class);
        llmRouter = Mockito.mock(LlmRouter.class);
        router = new ChatRouter(ruleRouter, llmRouter);
        ReflectionTestUtils.setField(router, "enabled", true);
        ReflectionTestUtils.setField(router, "llmFallback", true);
        ReflectionTestUtils.setField(router, "llmTimeoutMs", 1500L);
    }

    @Test
    @DisplayName("总开关关闭：直接 TOOL 且零成本，不查规则也不调 LLM（一键回滚路径）")
    void routerDisabledReturnsToolWithoutAnyLookup() {
        ReflectionTestUtils.setField(router, "enabled", false);

        RouteDecision d = router.route(TURNS, "帮我加个任务");

        assertEquals(ChatRoute.TOOL, d.route());
        assertEquals("router_disabled", d.rule());
        assertEquals(0, d.costMs());
        verifyNoInteractions(ruleRouter, llmRouter);
    }

    @Test
    @DisplayName("规则命中 TOOL：零成本直出，不调 LLM（漏任务0容忍）")
    void ruleHitToolSkipsLlm() {
        when(ruleRouter.route("帮我加个任务"))
                .thenReturn(new RouteDecision(ChatRoute.TOOL, "tool_verbs", 1));

        RouteDecision d = router.route(TURNS, "帮我加个任务");

        assertEquals(ChatRoute.TOOL, d.route());
        assertEquals("tool_verbs", d.rule());
        verify(llmRouter, never()).classify(anyList(), anyString(), eq(1500L));
    }

    @Test
    @DisplayName("规则命中 SIMPLE：直出，不调 LLM")
    void ruleHitSimpleSkipsLlm() {
        when(ruleRouter.route("你好"))
                .thenReturn(new RouteDecision(ChatRoute.SIMPLE, "chat_signals", 1));

        RouteDecision d = router.route(TURNS, "你好");

        assertEquals(ChatRoute.SIMPLE, d.route());
        verify(llmRouter, never()).classify(anyList(), anyString(), eq(1500L));
    }

    @Test
    @DisplayName("规则 UNKNOWN + LLM 判 TASK → TOOL")
    void llmTaskVerdictGoesToTool() {
        when(ruleRouter.route("考研什么时候报名"))
                .thenReturn(new RouteDecision(ChatRoute.UNKNOWN, "no_match", 1));
        when(llmRouter.classify(TURNS, "考研什么时候报名", 1500L)).thenReturn("TASK");

        RouteDecision d = router.route(TURNS, "考研什么时候报名");

        assertEquals(ChatRoute.TOOL, d.route());
        assertEquals("llm_TASK", d.rule());
    }

    @Test
    @DisplayName("规则 UNKNOWN + LLM 判 chat（小写）→ SIMPLE，大小写不敏感")
    void llmChatVerdictCaseInsensitive() {
        when(ruleRouter.route("你觉得考研难吗"))
                .thenReturn(new RouteDecision(ChatRoute.UNKNOWN, "no_match", 1));
        when(llmRouter.classify(TURNS, "你觉得考研难吗", 1500L)).thenReturn("chat");

        RouteDecision d = router.route(TURNS, "你觉得考研难吗");

        assertEquals(ChatRoute.SIMPLE, d.route());
        assertEquals("llm_CHAT", d.rule());
    }

    @Test
    @DisplayName("LLM 超时/异常返回 null → 默认 TOOL（安全超集 = 改造前行为）")
    void llmNullDefaultsToTool() {
        when(ruleRouter.route("线性代数怎么学比较好"))
                .thenReturn(new RouteDecision(ChatRoute.UNKNOWN, "no_match", 1));
        when(llmRouter.classify(TURNS, "线性代数怎么学比较好", 1500L)).thenReturn(null);

        RouteDecision d = router.route(TURNS, "线性代数怎么学比较好");

        assertEquals(ChatRoute.TOOL, d.route());
        assertEquals("llm_default_TOOL", d.rule());
    }

    @Test
    @DisplayName("LLM 输出不认识（垃圾文本）→ 默认 TOOL")
    void llmGarbageDefaultsToTool() {
        when(ruleRouter.route("嗯嗯"))
                .thenReturn(new RouteDecision(ChatRoute.UNKNOWN, "no_match", 1));
        when(llmRouter.classify(TURNS, "嗯嗯", 1500L)).thenReturn("好的没问题");

        RouteDecision d = router.route(TURNS, "嗯嗯");

        assertEquals(ChatRoute.TOOL, d.route());
        assertEquals("llm_default_TOOL", d.rule());
    }

    @Test
    @DisplayName("llm-fallback 关闭：UNKNOWN 原样透传，不调 LLM")
    void fallbackDisabledKeepsUnknown() {
        ReflectionTestUtils.setField(router, "llmFallback", false);
        when(ruleRouter.route("你觉得考研难吗"))
                .thenReturn(new RouteDecision(ChatRoute.UNKNOWN, "no_match", 1));

        RouteDecision d = router.route(TURNS, "你觉得考研难吗");

        assertEquals(ChatRoute.UNKNOWN, d.route());
        assertEquals("no_match", d.rule());
        verifyNoInteractions(llmRouter);
    }

    @Test
    @DisplayName("recentTurns 原样透传给 LLM（支持「那帮我删掉吧」类指代消解）")
    void recentTurnsPassedThrough() {
        when(ruleRouter.route("那帮我删掉吧"))
                .thenReturn(new RouteDecision(ChatRoute.UNKNOWN, "no_match", 1));
        when(llmRouter.classify(TURNS, "那帮我删掉吧", 1500L)).thenReturn("TASK");

        router.route(TURNS, "那帮我删掉吧");

        verify(llmRouter).classify(TURNS, "那帮我删掉吧", 1500L);
    }
}
