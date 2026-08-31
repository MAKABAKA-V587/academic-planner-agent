package com.studentagent.studentagent.service.router;

/**
 * 路由结果：SIMPLE = 闲聊/问答（精简链路，不带工具）；TOOL = 任务意图（进工具编排链）。
 * UNKNOWN 仅作为规则层的中间态，由 ChatRouter 二次裁决后不会外漏。
 */
public enum ChatRoute {
    SIMPLE,
    TOOL,
    UNKNOWN
}
