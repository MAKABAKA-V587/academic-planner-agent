package com.studentagent.studentagent.service.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 路由门面（分诊台）：规则路由优先（0ms），判不准时 LLM 路由兜底，
 * LLM 超时/异常/开关关闭时默认 TOOL（安全超集 = 完全等于改造前行为）。
 *
 * 降级链：规则失败 → LLM 路由 → 超时/异常 → 默认 TOOL
 * 总开关 agent.router.enabled=false → 跳过路由全量走 TOOL（一键回滚）。
 */
@Slf4j
@Service
public class ChatRouter {

    private final RuleBasedRouter ruleRouter;
    private final LlmRouter llmRouter;

    @Value("${agent.router.enabled:true}")
    private boolean enabled;

    @Value("${agent.router.llm-fallback:true}")
    private boolean llmFallback;

    @Value("${agent.router.llm-timeout-ms:1500}")
    private long llmTimeoutMs;

    public ChatRouter(RuleBasedRouter ruleRouter, LlmRouter llmRouter) {
        this.ruleRouter = ruleRouter;
        this.llmRouter = llmRouter;
    }

    /**
     * @param recentTurns 最近几轮对话（"用户：xxx" / "AI：xxx" 格式，解决"那帮我删掉吧"这类指代）
     * @param userMessage 本次用户消息
     */
    public RouteDecision route(List<String> recentTurns, String userMessage) {
        long start = System.currentTimeMillis();
        if (!enabled) {
            return new RouteDecision(ChatRoute.TOOL, "router_disabled", 0);
        }
        RouteDecision decision = ruleRouter.route(userMessage);
        if (decision.route() == ChatRoute.UNKNOWN && llmFallback) {
            String verdict = llmRouter.classify(recentTurns, userMessage, llmTimeoutMs);
            long cost = System.currentTimeMillis() - start;
            if ("TASK".equalsIgnoreCase(verdict)) {
                decision = new RouteDecision(ChatRoute.TOOL, "llm_TASK", cost);
            } else if ("CHAT".equalsIgnoreCase(verdict)) {
                decision = new RouteDecision(ChatRoute.SIMPLE, "llm_CHAT", cost);
            } else {
                // LLM 失败/超时/输出不认识 → 默认 TOOL（工具路径能处理一切，不会比现状差）
                decision = new RouteDecision(ChatRoute.TOOL, "llm_default_TOOL", cost);
            }
        }
        log.info("[router] msg={}... route={} rule={} cost={}ms",
                abbreviate(userMessage), decision.route(), decision.rule(), decision.costMs());
        return decision;
    }

    private String abbreviate(String msg) {
        if (msg == null) return "";
        return msg.length() <= 20 ? msg : msg.substring(0, 20);
    }
}
