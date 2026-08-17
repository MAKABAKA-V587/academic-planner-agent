package com.studentagent.studentagent.tool;

import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具调用上下文持有者。
 *
 * 优先使用 Spring AI ToolContext（由 ChatClient.toolContext(Map) 注入，流式端点工具在订阅线程
 * 执行，ThreadLocal 会丢失，ToolContext 参数才是可靠通道）；ThreadLocal 仅作为阻塞端点同线程调用时的兜底。
 */
public class ToolContextHolder {

    private static final ThreadLocal<Map<String, Object>> CONTEXT = new ThreadLocal<>();

    public static void set(Long sessionId, Long userId) {
        Map<String, Object> m = new HashMap<>();
        m.put("sessionId", sessionId);
        m.put("userId", userId);
        CONTEXT.set(m);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /** 从 ToolContext 参数取 userId，缺失时回退 ThreadLocal */
    public static Long userId(ToolContext toolContext) {
        Object v = ctxValue(toolContext, "userId");
        if (v != null) {
            if (v instanceof Number n) return n.longValue();
            return Long.valueOf(v.toString());
        }
        return null;
    }

    /** 从 ToolContext 参数取 sessionId，缺失时回退 ThreadLocal */
    public static Long sessionId(ToolContext toolContext) {
        Object v = ctxValue(toolContext, "sessionId");
        if (v != null) {
            if (v instanceof Number n) return n.longValue();
            return Long.valueOf(v.toString());
        }
        return null;
    }

    /** 取联网搜索开关（来自 ToolContext 的 webSearch，缺省 false） */
    public static boolean webSearchEnabled(ToolContext toolContext) {
        Object v = ctxValue(toolContext, "webSearch");
        return v instanceof Boolean b && b;
    }

    private static Object ctxValue(ToolContext toolContext, String key) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object v = toolContext.getContext().get(key);
            if (v != null) return v;
        }
        Map<String, Object> local = CONTEXT.get();
        return local != null ? local.get(key) : null;
    }
}
