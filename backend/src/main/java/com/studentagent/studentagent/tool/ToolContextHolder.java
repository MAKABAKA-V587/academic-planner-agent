package com.studentagent.studentagent.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具调用上下文持有者（LangChain4j 版）。
 *
 * LangChain4j 的 @Tool 方法没有 ToolContext 参数注入，用户身份/会话/联网开关
 * 通过 ThreadLocal 传递。
 *
 * 注意线程边界：Flux 流式端点由 Controller 以 subscribeOn(boundedElastic) 订阅，
 * 工具循环运行在 boundedElastic 线程而非 Tomcat 请求线程。因此 set/clear 必须包在
 * chatWithTools 调用处（见 ChatService.callWithToolContext），在执行工具循环的线程上
 * 完成，不能在 Controller/Service 入口线程提前 set —— 否则工具读到 userId=null。
 */
public class ToolContextHolder {

    private static final ThreadLocal<Map<String, Object>> CONTEXT = new ThreadLocal<>();

    public static void set(Long sessionId, Long userId, boolean webSearch) {
        Map<String, Object> m = new HashMap<>();
        m.put("sessionId", sessionId);
        m.put("userId", userId);
        m.put("webSearch", webSearch);
        CONTEXT.set(m);
    }

    public static void clear() {
        CONTEXT.remove();
    }

    /** 当前线程的用户 ID，无上下文时返回 null */
    public static Long userId() {
        Object v = ctxValue("userId");
        if (v != null) {
            if (v instanceof Number n) return n.longValue();
            return Long.valueOf(v.toString());
        }
        return null;
    }

    /** 当前线程的会话 ID，无上下文时返回 null */
    public static Long sessionId() {
        Object v = ctxValue("sessionId");
        if (v != null) {
            if (v instanceof Number n) return n.longValue();
            return Long.valueOf(v.toString());
        }
        return null;
    }

    /** 当前线程的联网搜索开关（缺省 false） */
    public static boolean webSearchEnabled() {
        Object v = ctxValue("webSearch");
        return v instanceof Boolean b && b;
    }

    private static Object ctxValue(String key) {
        Map<String, Object> local = CONTEXT.get();
        return local != null ? local.get(key) : null;
    }
}
