package com.studentagent.studentagent.service.router;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 第一级规则路由（0ms 零成本）：任务动词整句扫描优先，
 * 漏任务代价是功能丢失（0 容忍），误任务只多花 token（可容忍），
 * 所以方向必须偏向 TOOL：规则判不准时返回 UNKNOWN 交给 LLM 路由。
 */
@Component
public class RuleBasedRouter {

    /** 任务意图：日历操作 / 计划 / 复习 / 搜索（任一命中 → TOOL）。
     *  「帮我」单独收录：该前缀几乎总是任务请求，按"宁误不漏"原则偏向 TOOL。 */
    private static final Pattern TOOL_PATTERN = Pattern.compile(
            "帮我|添加|加入|加个|记一下|建个|创建|安排上|" +
            "删除|删掉|去掉|取消|清空|清除|" +
            "制定.{0,6}计划|学习计划|备考计划|复习计划|安排复习|复习.{0,6}安排|艾宾浩斯|" +
            "日历|日程|有什么安排|今天要做什么|我的安排|" +
            "联网|搜索|搜一下|搜一搜");

    /** 明确闲聊信号：问候/感谢/能力询问（开头匹配或整词命中 → SIMPLE） */
    private static final Pattern SIMPLE_PATTERN = Pattern.compile(
            "^\\s*(你好|您好|嗨|hi|hello|在吗|早啊|早安|午安|晚安|晚上好|下午好)" +
            "|谢谢|多谢|辛苦了|麻烦了" +
            "|你是谁|你叫什么|你能做什么|你会什么|介绍一下你自己");

    public RouteDecision route(String userMessage) {
        long start = System.currentTimeMillis();
        if (userMessage == null || userMessage.isBlank()) {
            return new RouteDecision(ChatRoute.SIMPLE, "empty", elapsed(start));
        }
        // 任务动词优先：即使句中带问候语（如"你好，帮我加个任务"）也判 TOOL
        if (TOOL_PATTERN.matcher(userMessage).find()) {
            return new RouteDecision(ChatRoute.TOOL, "tool_verbs", elapsed(start));
        }
        if (SIMPLE_PATTERN.matcher(userMessage).find()) {
            return new RouteDecision(ChatRoute.SIMPLE, "chat_signals", elapsed(start));
        }
        return new RouteDecision(ChatRoute.UNKNOWN, "no_match", elapsed(start));
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
