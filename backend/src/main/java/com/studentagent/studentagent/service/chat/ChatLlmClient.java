package com.studentagent.studentagent.service.chat;

import com.studentagent.studentagent.service.review.ToolResultReviewAgent;
import com.studentagent.studentagent.tool.ToolCallExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * LLM 阻塞调用原语：限流/瞬时错误退避重试 + 手动工具循环（含 enforceTool 纠偏）。
 * 拆分自 ChatService，供阻塞对话端点与流式编排器（工具兜底）共同复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLlmClient {

    private final ChatModel chatModel;
    private final ToolCallExecutor toolCallExecutor;
    private final ToolResultReviewAgent reviewAgent;

    private static final int MAX_TOOL_ROUNDS = 6;

    /** 内部事件名：工具结果已并入消息列表，正在生成最终回答（最后一轮 LLM 调用，耗时最长） */
    public static final String EVENT_GENERATING = "_generating_";
    /** 内部事件名：首轮 LLM 正在决定调用哪个工具（该调用本身需数秒，需状态提示消除空白） */
    public static final String EVENT_THINKING = "_thinking_";

    // ==================== token 用量累计（成本控制记账） ====================
    // ChatLlmClient 不感知 userId，只在调用点累计用量；由知道 userId 的上层
    // （ChatService 阻塞入口 / Orchestrator.callWithToolContext / fallbackStream）
    // 在调用结束后 drainUsage() 取走并交给 TokenBudgetService 记账。
    // 阻塞调用（含限流重试、工具循环多轮）在单线程内同步完成，ThreadLocal 累计安全。

    private record Acc(long in, long out) {}

    private static final ThreadLocal<Acc> USAGE_ACC = new ThreadLocal<>();

    /** 取出并清零本次阻塞调用累计的 token 用量（上层负责交给 TokenBudgetService 记账） */
    public TokenBudgetService.Usage drainUsage() {
        Acc acc = USAGE_ACC.get();
        USAGE_ACC.remove();
        return acc == null ? new TokenBudgetService.Usage(0, 0) : new TokenBudgetService.Usage(acc.in, acc.out);
    }

    /** 从 ChatResponse 提取 tokenUsage 累加（计数字段可能为 null，统一归零；失败不影响主流程） */
    private void accumulateUsage(ChatResponse response) {
        try {
            TokenBudgetService.Usage usage = TokenBudgetService.Usage.fromChat(response.tokenUsage());
            if (usage.total() <= 0) {
                return;
            }
            Acc cur = USAGE_ACC.get();
            USAGE_ACC.set(cur == null
                    ? new Acc(usage.inputTokens(), usage.outputTokens())
                    : new Acc(cur.in + usage.inputTokens(), cur.out + usage.outputTokens()));
        } catch (Exception e) {
            log.debug("token用量提取失败(不影响对话): {}", e.getMessage());
        }
    }

    /**
     * 带限流退避的阻塞调用：DeepSeek 高峰期会返回 429（code 50609 System is too busy now），
     * 此类错误按 1s/2s/4s 退避最多重试 3 次；其余异常不重试直接抛出。
     * 此外，模型调用成功但返回空内容（DeepSeek 偶发）也视为可重试条件，同样退避重试，
     * 避免把"成功但空回复"误报为服务繁忙。
     */
    public String callWithRateRetry(Supplier<String> call) {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String result = call.get();
                if (result != null && !result.isBlank()) {
                    return result;
                }
                // 调用成功但内容为空 → 退避重试
                if (attempt < 2) {
                    int waitMs = 1000 * (attempt + 1);
                    log.warn("模型返回空内容，等待{}ms后第{}次重试", waitMs, attempt + 2);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                return result; // 3次仍为空 → 返回空，由上层区分文案
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                // 限流和网络瞬时错误（连接被服务端回收 closed / 超时 / 重置）均退避重试：
                // SiliconFlow 空闲连接常被服务端提前关闭，复用死连接会报 "closed"
                if ((isRateLimited(msg) || isTransientNetworkError(msg)) && attempt < 2) {
                    // SiliconFlow 高峰 "System is too busy" 限流窗口常达数秒~数十秒，
                    // 退避加大到 2s/4s 提高错开限流窗口的概率
                    int waitMs = 2000 * (attempt + 1);
                    log.warn("模型调用瞬时失败({})，等待{}ms后第{}次重试", msg, waitMs, attempt + 2);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        if (last != null) {
            throw new RuntimeException("模型调用失败: " + last.getMessage(), last);
        }
        return null;
    }

    /** 判断是否 DeepSeek/OpenAI 限流错误（429 / code 50609 / rate limiting / too busy） */
    private boolean isRateLimited(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("429") || lower.contains("rate limiting")
                || lower.contains("too busy") || lower.contains("50609");
    }

    /** 判断是否网络类瞬时错误（连接被服务端回收/超时/重置，重试即可恢复） */
    private boolean isTransientNetworkError(String msg) {
        String lower = msg.toLowerCase();
        return lower.contains("closed") || lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("connection reset") || lower.contains("connection refused")
                || lower.contains("eof") || lower.contains("stream closed");
    }

    /** 无工具阻塞调用：单轮 chat，返回模型文本回复（DeepSeek 偶发空内容返回 null） */
    public String chatNoTools(List<ChatMessage> messages) {
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(messages)
                .build());
        accumulateUsage(response);
        AiMessage ai = response.aiMessage();
        return ai != null ? ai.text() : null;
    }

    public String chatWithTools(List<ChatMessage> messages, boolean enforceTool) {
        return chatWithTools(messages, enforceTool, null);
    }

    /**
     * 带工具阻塞调用：手动工具循环（LangChain4j 无 AiServices 时的手写等价物）。
     * 每轮 chat 若模型请求调用工具，则通过 ToolCallExecutor 依次执行并把结果以
     * ToolExecutionResultMessage 追加回消息列表，再进入下一轮；直到模型直接输出
     * 文本回复或超过 MAX_TOOL_ROUNDS 上限。
     * onToolStart：每个工具执行前 / 最终回答生成前回调（参数为工具方法名或 EVENT_GENERATING），
     * 供流式端点推送状态事件；阻塞端点传 null。
     */
    public String chatWithTools(List<ChatMessage> messages, boolean enforceTool, Consumer<String> onToolStart) {
        List<ToolSpecification> specs = toolCallExecutor.specifications();
        List<ChatMessage> current = new ArrayList<>(messages);
        // 取最后一条用户消息，供评审Agent做结果相关性判断
        String userMessage = null;
        for (int i = current.size() - 1; i >= 0; i--) {
            if (current.get(i) instanceof UserMessage um) {
                userMessage = um.singleText();
                break;
            }
        }
        boolean forceRequired = false;
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(current)
                    .toolSpecifications(specs);
            // 纠偏轮：tool_choice=required 强制模型必须发起 tool_calls（SiliconFlow/DeepSeek-V3 已验证支持）。
            // 只强制这一轮，随后立即恢复 auto，否则模型每轮都被迫调工具、永远无法给出最终文字回复
            if (forceRequired) {
                requestBuilder.parameters(OpenAiChatRequestParameters.builder()
                        .toolChoice(ToolChoice.REQUIRED)
                        .build());
                forceRequired = false;
            }
            ChatResponse response = chatModel.chat(requestBuilder.build());
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                return null;
            }
            if (aiMessage.hasToolExecutionRequests()) {
                current.add(aiMessage);
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    if (onToolStart != null) onToolStart.accept(request.name());
                    String result = toolCallExecutor.execute(request);
                    // 评审Agent：规则评审（防幻觉/防跨用户泄露）+ LLM 语义评审（只读工具），失败降级直通
                    result = reviewAgent.review(userMessage, request, result);
                    current.add(ToolExecutionResultMessage.from(request, result));
                }
                // 工具结果并入完毕，下一轮 LLM 生成最终回答（耗时较长），推送"整理中"状态
                if (onToolStart != null) onToolStart.accept(EVENT_GENERATING);
                continue;
            }
            // 规则强命中工具意图但模型首轮直接文字回复（甚至幻觉"已添加/正在调用"）→
            // 追加追问并以 tool_choice=required 强制重试一轮。只纠偏一次防死循环。
            if (enforceTool && round == 0 && !forceRequired) {
                log.info("TOOL规则命中但模型未调用工具，以 tool_choice=required 强制重试");
                current.add(aiMessage);
                current.add(UserMessage.from(
                        "上一条请求需要通过调用工具完成（查询知识/日程、增删改日历任务等都有对应工具）。"
                                + "请立即调用最匹配的工具完成该请求。"));
                forceRequired = true;
                continue;
            }
            return aiMessage.text();
        }
        log.warn("工具循环超过{}轮仍未收敛，返回最后一轮内容", MAX_TOOL_ROUNDS);
        return "抱歉，工具调用次数过多，请重试或简化请求";
    }
}
