package com.studentagent.studentagent.service.review;

import com.studentagent.studentagent.tool.LearningPlanTool;
import com.studentagent.studentagent.tool.ToolContextHolder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评审Agent（阶段2）：工具结果质检员。
 *
 * 在工具循环内对每个工具结果做两级评审：
 * 1. 规则评审（0ms，所有工具必走）：空结果防幻觉、跨用户 userId 比对防泄露、显式错误直通、
 *    计划内容质量（generateStudyPlan 未传知识点大纲时提示模型如实说明"通用框架"）；
 * 2. LLM 评审（约 1~2s，仅 searchKnowledge/webSearch/queryEvents 等只读语义工具）：
 *    判断结果与用户问题是否相关，INVALID 时替换为"不可信"提示。
 *
 * 降级链：开关关闭/规则异常/LLM 超时异常/输出不认识 → 一律放行原始结果，
 * 系统行为退化为改造前（永不比现状差）。
 */
@Slf4j
@Service
public class ToolResultReviewAgent {

    /** 结果文本中 userId 字段的两种常见形态：JSON "userId":11 与文本 userId=11 */
    private static final Pattern USER_ID_PATTERN = Pattern.compile("\"?userId\"?\\s*[:=]\\s*(\\d+)");

    /** 工具参数 JSON 中 topics 字段的取值（含转义引号容错），空/缺失视为未传知识点大纲 */
    private static final Pattern TOPICS_PATTERN = Pattern.compile("\"topics\"\\s*:\\s*\"([^\"]*)\"");

    /** 结果截断长度：评审只看开头足够判断相关性，控制 prompt 成本 */
    private static final int RESULT_SNIPPET = 800;

    private static final String EMPTY_RESULT_MESSAGE =
            "错误：工具返回空结果，操作未完成。请如实告知用户暂时无法完成该操作，不要编造成功信息。";

    private static final String REVIEW_SYSTEM_PROMPT =
            "你是Agent系统中的工具结果评审员。判断工具结果能否支撑回答用户的问题。"
            + "只输出一行：VALID，或 INVALID:<一句话原因>。不要输出其他任何内容。";

    private final ChatModel chatModel;

    @Value("${agent.review.enabled:true}")
    private boolean enabled;

    @Value("${agent.review.llm-timeout-ms:2000}")
    private long llmTimeoutMs;

    @Value("${agent.review.llm-tools:searchKnowledge,webSearch,queryEvents}")
    private String llmTools;

    public ToolResultReviewAgent(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 评审一个工具结果，返回可能被替换后的结果文本。
     *
     * @param userMessage 触发本次工具调用的用户消息（用于 LLM 相关性判断）
     * @param request     模型发起的工具调用请求
     * @param rawResult   工具原始执行结果
     */
    public String review(String userMessage, ToolExecutionRequest request, String rawResult) {
        if (!enabled || rawResult == null) {
            return rawResult;
        }
        try {
            String ruled = ruleReview(request, rawResult);
            if (ruled != null) {
                return ruled;
            }
            if (isLlmTool(request.name())) {
                return llmReview(userMessage, request, rawResult);
            }
            return rawResult;
        } catch (Exception e) {
            log.warn("[review] 规则评审异常，直通原始结果: {}", e.getMessage());
            return rawResult;
        }
    }

    /** 规则评审：返回 null 表示规则未命中（继续后续评审），否则返回替换/直通后的结果 */
    private String ruleReview(ToolExecutionRequest request, String result) {
        String trimmed = result.strip();
        if (trimmed.isEmpty()) {
            // R1 空结果：防止模型脑补"已成功"
            log.info("[review] 空结果拦截 tool={}", request.name());
            return EMPTY_RESULT_MESSAGE;
        }
        // R2 显式错误：直通，模型需要看到错误才能向用户解释
        if (trimmed.startsWith("错误：") || trimmed.startsWith("失败") || trimmed.startsWith("无法")) {
            return result;
        }
        // R3 跨用户泄露：结果中的 userId 与当前登录用户不一致时整条拦截
        Long currentUserId = ToolContextHolder.userId();
        if (currentUserId != null) {
            Matcher m = USER_ID_PATTERN.matcher(trimmed);
            while (m.find()) {
                long foundId = Long.parseLong(m.group(1));
                if (foundId != currentUserId) {
                    log.warn("[review] 跨用户泄露拦截 tool={} resultUserId={} currentUserId={}",
                            request.name(), foundId, currentUserId);
                    return "错误：工具返回的数据不属于当前用户，已拦截。请如实告知用户暂时无法查询该信息。";
                }
            }
        }
        // R4 计划内容质量：未传知识点大纲时工具只能输出通用模板任务，
        // 追加提示要求模型如实告知"通用框架"，禁止包装成针对性计划（不拦截：事件已入库，替换会状态不一致）
        if ("generateStudyPlan".equals(request.name())
                && !hasTopicsArgument(request.arguments())
                && LearningPlanTool.containsGenericTasks(result)) {
            log.info("[review] 计划内容质量提示 tool={} (无topics,通用模板内容)", request.name());
            return result + "\n\n⚠️ [评审Agent-内容质量] 本次计划任务为通用学习框架（未传入针对性知识点大纲）。"
                    + "你必须向用户如实说明这是通用框架，禁止将其描述为针对该科目定制的计划；"
                    + "并建议用户补充学习基础、目标和时间安排后，重新生成个性化计划。";
        }
        return null;
    }

    /** 从工具调用参数 JSON 中提取 topics 字段，判断模型是否传入了知识点大纲 */
    private boolean hasTopicsArgument(String arguments) {
        if (arguments == null || arguments.isBlank()) return false;
        Matcher m = TOPICS_PATTERN.matcher(arguments);
        return m.find() && !m.group(1).isBlank();
    }

    /** LLM 评审：仅对语义敏感的只读工具触发，失败一律降级放行 */
    private String llmReview(String userMessage, ToolExecutionRequest request, String result) {
        long start = System.currentTimeMillis();
        try {
            String snippet = result.length() <= RESULT_SNIPPET ? result : result.substring(0, RESULT_SNIPPET);
            String prompt = "用户问题：" + (userMessage == null ? "（无）" : userMessage)
                    + "\n工具名：" + request.name()
                    + "\n工具参数：" + request.arguments()
                    + "\n工具结果：" + snippet
                    + "\n\n请输出 VALID 或 INVALID:<原因>";
            String verdict = CompletableFuture
                    .supplyAsync(() -> chatModel.chat(List.of(
                            SystemMessage.from(REVIEW_SYSTEM_PROMPT),
                            UserMessage.from(prompt))))
                    .get(llmTimeoutMs, TimeUnit.MILLISECONDS)
                    .aiMessage().text().strip();
            long cost = System.currentTimeMillis() - start;
            if (verdict.startsWith("VALID")) {
                log.info("[review] LLM评审通过 tool={} cost={}ms", request.name(), cost);
                return result;
            }
            if (verdict.startsWith("INVALID")) {
                log.info("[review] LLM评审拦截 tool={} verdict={} cost={}ms", request.name(), verdict, cost);
                return "评审Agent判定该工具结果不可用（" + verdict.substring("INVALID".length()).replaceFirst("^[:：]", "").strip()
                        + "）。请如实告知用户未查到可靠信息，不要引用该结果。";
            }
            log.warn("[review] LLM输出不认识，降级放行 tool={} verdict={}", request.name(), verdict);
            return result;
        } catch (Exception e) {
            log.warn("[review] LLM评审失败，降级放行 tool={} cost={}ms err={}",
                    request.name(), System.currentTimeMillis() - start, e.getMessage());
            return result;
        }
    }

    private boolean isLlmTool(String toolName) {
        Set<String> tools = new HashSet<>(Arrays.asList(llmTools.split(",")));
        return tools.contains(toolName);
    }
}
