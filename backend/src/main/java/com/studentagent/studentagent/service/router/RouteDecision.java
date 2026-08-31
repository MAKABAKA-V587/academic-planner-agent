package com.studentagent.studentagent.service.router;

/**
 * 路由决策：命中的规则 + 耗时（可观测，为面试数据积累服务）。
 */
public record RouteDecision(ChatRoute route, String rule, long costMs) {
}
