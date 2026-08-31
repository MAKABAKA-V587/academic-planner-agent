package com.studentagent.studentagent;

import com.studentagent.studentagent.service.router.ChatRoute;
import com.studentagent.studentagent.service.router.RouteDecision;
import com.studentagent.studentagent.service.router.RuleBasedRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 路由 Agent 规则层评测（40 条用例）。
 * 验收标准（见 毕业设计/多智能体阶段1-路由设计.md 第6节）：
 *  - 漏任务（TOOL 用例被判 SIMPLE）= 0（0 容忍）
 *  - SIMPLE 用例允许 UNKNOWN（交 LLM 路由兜底），但禁止被误判为 TOOL 超过 20%
 */
class RuleBasedRouterTest {

    private final RuleBasedRouter router = new RuleBasedRouter();

    private ChatRoute route(String msg) {
        return router.route(msg).route();
    }

    // ========== TOOL 用例（20 条）：漏任务 0 容忍，全部必须 TOOL ==========
    private static final List<String> TOOL_CASES = List.of(
            "帮我今天添加一个运动任务",
            "帮我明天下午3点安排背单词",
            "在日历里加一个复习高数的事件",
            "删掉明天的背单词任务",
            "帮我把今天的学习任务清空",
            "把日历全部清空",
            "帮我制定一个考研数学复习计划",
            "制定一份英语学习计划",
            "我学完了链表翻转，帮我安排复习",
            "用艾宾浩斯遗忘曲线帮我排期线性代数",
            "我有什么安排",
            "今天要做什么",
            "查一下我这周的日程",
            "下周三之前帮我安排三次模拟测试",
            "帮我搜一下今年的考研报名时间",
            "联网查一下明天天气",
            "给我建个提醒，周五交论文",
            "把复习线代这件事记一下",
            "取消掉周六的锻炼安排",
            "你好，顺便帮我今天加个运动任务"          // 混合意图：问候+任务 → 必须 TOOL
    );

    // ========== SIMPLE 用例（20 条）：允许 UNKNOWN（LLM 兜底），但规则禁止大量误判 TOOL ==========
    private static final List<String> SIMPLE_CASES = List.of(
            "你好",
            "您好呀",
            "hi",
            "hello",
            "在吗",
            "谢谢你！",
            "辛苦了",
            "晚安",
            "早安",
            "你是谁",
            "你叫什么名字",
            "你能做什么",
            "你会什么技能",
            "介绍一下你自己",
            "谢谢，今天先这样",
            "嗯嗯好的，晚安啦",
            "哎呀，谢谢啦，辛苦你了",
            "嗨，好久不见",
            "早上好啊",
            "在吗？跟你说个事"                        // 混合闲聊开头，无任务动词
    );

    @Test
    @DisplayName("TOOL 用例：漏任务必须为 0（全部判 TOOL）")
    void toolCasesNeverMissed() {
        for (String msg : TOOL_CASES) {
            assertEquals(ChatRoute.TOOL, route(msg), "漏任务: " + msg);
        }
    }

    @Test
    @DisplayName("SIMPLE 用例：误判 TOOL 率 ≤ 20%（其余为 SIMPLE 或 UNKNOWN 交 LLM 兜底）")
    void simpleCasesMissRateBounded() {
        long misroutedToTool = SIMPLE_CASES.stream().filter(m -> route(m) == ChatRoute.TOOL).count();
        double missRate = (double) misroutedToTool / SIMPLE_CASES.size();
        org.junit.jupiter.api.Assertions.assertTrue(
                missRate <= 0.2,
                "误任务率超 20%: " + misroutedToTool + "/" + SIMPLE_CASES.size());
    }

    @Test
    @DisplayName("混合意图：问候+任务 → TOOL（规则层任务动词优先）")
    void mixedIntentGoesToTool() {
        assertEquals(ChatRoute.TOOL, route("你好，帮我加个复习任务"));
        assertEquals(ChatRoute.TOOL, route("谢谢，另外帮我制定个学习计划"));
    }

    @Test
    @DisplayName("UNKNOWN：中性问题交 LLM 路由兜底，不武断判 SIMPLE（防漏任务）")
    void ambiguousGoesToUnknown() {
        assertNotEquals(ChatRoute.SIMPLE, route("你觉得考研难吗"));
        assertNotEquals(ChatRoute.SIMPLE, route("线性代数怎么学比较好"));
    }

    @Test
    @DisplayName("路由决策可观测：规则命中与耗时被记录")
    void decisionCarriesRuleAndCost() {
        RouteDecision d = router.route("帮我今天添加一个运动任务");
        assertEquals("tool_verbs", d.rule());
        org.junit.jupiter.api.Assertions.assertTrue(d.costMs() >= 0);
    }
}
