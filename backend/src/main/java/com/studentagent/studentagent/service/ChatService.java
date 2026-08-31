package com.studentagent.studentagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studentagent.studentagent.entity.ChatSessionMaterial;
import com.studentagent.studentagent.entity.MemoryRecord;
import com.studentagent.studentagent.entity.StudentProfile;
import com.studentagent.studentagent.entity.StudyMaterial;
import com.studentagent.studentagent.entity.SysUser;
import com.studentagent.studentagent.mapper.ChatSessionMaterialMapper;
import com.studentagent.studentagent.mapper.MemoryRecordMapper;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.ProfileMapper;
import com.studentagent.studentagent.mapper.SessionMapper;
import com.studentagent.studentagent.mapper.StudyMaterialMapper;
import com.studentagent.studentagent.mapper.UserMapper;
import com.studentagent.studentagent.service.router.ChatRoute;
import com.studentagent.studentagent.service.router.ChatRouter;
import com.studentagent.studentagent.service.router.RouteDecision;
import com.studentagent.studentagent.service.review.ToolResultReviewAgent;
import com.studentagent.studentagent.tool.ToolCallExecutor;
import com.studentagent.studentagent.tool.ToolContextHolder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final CalendarService calendarService;
    private final StringRedisTemplate redisTemplate;
    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final ProfileMapper profileMapper;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ToolCallExecutor toolCallExecutor;
    private final ChatRouter chatRouter;
    private final ToolResultReviewAgent reviewAgent;
    private final ObjectMapper objectMapper;
    private final MemoryExtractService memoryExtractService;
    private final ProfileService profileService;
    private final UserMapper userMapper;
    private final MemoryRecordMapper memoryRecordMapper;
    private final ChatSessionMaterialMapper chatSessionMaterialMapper;
    private final StudyMaterialMapper studyMaterialMapper;

    private static final String HISTORY_KEY_PREFIX = "chat:history:";
    private static final int MAX_ROUNDS = 8;
    private static final int CACHE_TTL_SECONDS = 3600;
    private static final double RECALL_THRESHOLD = 0.7;
    private static final int RECALL_TOP_K = 3;
    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final int MAX_TOOL_ROUNDS = 6;

    /** 核心回答规范：阻塞/流式共用 */
    private static final String CORE_RULES = """
            【回答规范】
        1. 简洁清晰、分点说明、提供可操作的建议。
        2. 生成列表/计划/单词表等内容时必须完整，不得截断或只给示例；若承诺了具体数量（如"附20词表""30个单词"），必须按承诺数量**逐条完整列出**，禁止用省略号、缩略语或"等/其余类似"代替；每一条都必须带编号（1. 2. 3. …），不得使用无编号列表，输出完毕后必须自检数量与承诺一致；直接输出内容本身，减少"下面是/以上是/本表包含"之类的说明性文字。
        3. 表格用 Markdown 语法（|列1|列2|），不要用代码块包裹。
        4. 无法生成或发送文件，所有内容直接在回答中展示。
        5. 禁止虚假操作叙述：未真实执行的操作（如调用工具、导入日历、发送内容）严禁在回答中声称"正在执行/已完成/已导入N条"；只能给出用户可直接执行的说明（如带日期的 Markdown 表格）。
        6. 记忆时效性：【用户历史学习特征】中的条目可能带有时间标注（如"3周前"），越久远可信度越低；若某条历史特征与用户当前表述矛盾或明显过时，优先以用户当前说法为准，并可主动询问用户确认是否仍有效，严禁把过时记忆当作当前事实。
        7. 联网数据真实性：涉及实时数据（金价、天气、新闻、考试政策等）必须以 webSearch 工具的真实返回为准；未成功调用工具或工具返回不可用时，禁止编造具体数字/价格/日期，应如实说明"暂时无法获取实时数据"。
        8. 链接真实性：严禁编造链接或 URL（如"点击此处查看""示例链接""[xxx](xxx)"）；你无法验证链接是否真实存在时，禁止给出任何链接，改用文字描述下一步操作（如"回复我即可生成明日词表"）。
            
            【记忆提取】
            回复末尾追加记忆块（仅当用户本轮表达了新的学习特征，无则省略）：
            
            ---MEMORY---
            薄弱科目-科目名-具体描述
            学习目标-科目名-具体描述
            知识掌握-科目名-具体描述
            考试计划-科目名-具体描述
            学习习惯-具体描述
            用户昵称-昵称-具体昵称
            ---END---
            
            类别判定（严格区分，禁止混淆）：
            - 薄弱科目：用户说了"不会/不懂/薄弱/很差/记不住/搞不懂/头疼"等才用。说"想学XX"不是薄弱！
            - 学习目标：用户说了"想学/要学/打算学/准备学"。
            - 知识掌握：用户表示已学会/掌握了。
            - 用户昵称：仅当用户明确告诉了你TA的名字或昵称（如"我叫小明""可以叫我XX"）时才提取。
            - 科目必须是真实科目名，禁止写"未明确""暂无"等占位词。
            仅提取用户明确表达的信息，不要推测。
            """;

    /** 阻塞端点用：核心规范 + 工具能力 */
    private static final String TOOL_SYSTEM_PROMPT = CORE_RULES + "\n" + """
            【工具能力】（必须调用对应函数，禁止口头说"已添加/已删除/已搜索"）
            
            【工具调用铁律】
            1. 用户请求"生成学习计划并导入日历/把安排添加到日历/添加日程事件"时，必须先调用对应工具
               （generateStudyPlan / addEvent / queryEvents）获取真实结果，再基于工具返回组织回答。
               生成学习计划必须调用 generateStudyPlan 工具（工具会自动创建日历事件并返回完整计划），
               你只需基于工具返回展示计划，禁止自行编造完整计划文本。
            1.1 用户请求"添加/安排单个任务或事件"（如"今天加一个运动任务""明天下午3点背单词"）时，
               必须直接调用 addEvent 创建，调用成功后简短确认即可（如"已添加：运动任务（2026-08-17）"），
               禁止再输出表格、建议清单或"请稍等/我来处理"等过程台词；不要先给运动建议再询问，直接添加。
            2. 严禁输出导入"过程/结果"叙事：不得写"我现在将调用日历工具…请稍候…工具调用中…
               ✅日历导入完成！所有事件已从X至Y添加到你的日历"这类台词。日历事件只能通过真实工具调用
               产生；只有当你确实调用了工具并收到返回结果时，才能提及导入结果。
            3. 若未调用任何工具，不得声称已添加/已导入任何日历事件。
            
            日历管理：addEvent / queryEvents / deleteEvent / clearToday / clearAll
            - addEvent("标题","yyyy-MM-dd","yyyy-MM-dd","task")
            - queryEvents("yyyy-MM-dd","yyyy-MM-dd")
            - deleteEvent("标题","")
            - clearToday()  清空今日（用户说"清空今天"）
            - clearAll()    清空全部（仅当用户明确说"全部清空"，否则用 clearToday）
            
            知识库检索：searchKnowledge("科目","知识点")
            - 触发：问"怎么做/什么是/如何/介绍一下"等技术或知识点问题
            
            【知识库引用】搜索结果末尾的【知识来源】标注内容出处，引用知识库作答时必须在回答末尾原样附上
            【知识来源】信息（如 【知识来源】本地知识库「数据结构 - 链表」），便于用户溯源；禁止编造来源。
            
            学习计划：generateStudyPlan("科目","目标描述")
            - 触发：说"制定学习计划/生成复习计划/备考规划"等
            
            艾宾浩斯复习排期：scheduleReviewPlan("科目","知识点1、知识点2")
            - 触发：用户说"学了XX/学完了XX，帮我安排复习""按艾宾浩斯/遗忘曲线安排复习""安排复习计划巩固"等，
              或用户明确表达了刚学过的知识点并希望加深记忆
            - 工具会按 当天/1/2/4/7/15 天 自动创建日历复习事件，无需再输出日历表格
            - knowledgePoints 必须填本次的**具体内容标识**（如"第一单元单词""List 2 的高频词""微分方程"），
              不同批次要用不同的标识，方便区分；禁止只填科目名或"单词"这类笼统描述
            - 调用前如有疑问，先和用户确认本次要复习的知识点清单；知识很明确时直接调用
            
            【生成计划/日程时】日期必须写绝对日期（yyyy-MM-dd 或 8月5日），禁止只写"第X-Y周""D1""下周"这类相对格式；
            用户没给开始日期时，默认从今天（见下方日期参考）开始推算。表格统一为 | 日期 | 标题 | 类型 |，日期列用 yyyy-MM-dd。
            （此表格格式仅适用于 generateStudyPlan 输出的完整计划；addEvent 添加单个事件时无需输出表格。）
            
            规则：用户问"今天能干什么/有什么推荐/帮我安排"时，先调 queryEvents 查日历。
            有任务则列出，无任务则结合用户学习特征主动推荐，禁止只说"没有安排"。
            """;

    /** 流式端点用：核心规范 + 日历预注入（无工具，但提前把日历数据注入上下文） */
    private static final String STREAM_SYSTEM_PROMPT = CORE_RULES + "\n" + """
            【重要】你无法直接操作日历，但用户问日历/任务时，将答复整理成带日期的 Markdown 表格，
            用户可一键导入到日历。表格格式：| 日期 | 标题 | 类型 |，如 | 2026-08-06 | 复习高数 | 学习 |
            日期必须用绝对日期（yyyy-MM-dd），禁止用"第X-Y周""下周"等相对格式；无开始日期时默认从今天（见日期参考）起算。
            
            【禁止编造操作】你没有日历/导入工具。严禁输出"正在调用日历工具…请稍候…工具调用中…
            ✅日历导入完成/已导入N个事件"这类表述——你根本没有执行导入的能力，只能把计划整理成上面的
            Markdown 表格供用户一键手动导入，并在表格后说明"点击『导入到日历』即可添加"。
            """;

    private static final String WEBSEARCH_HINT = """
            【联网搜索已启用】当前你可以使用 webSearch 工具搜索互联网获取最新信息。
            遇到需要实时数据的问题（如金价、天气、考试政策变化、最新资讯等），请主动调用 webSearch 搜索。
            """;

    /** SIMPLE 路由专用精简提示词：不带工具说明书，省 token 且杜绝工具误触发 */
    private static final String SIMPLE_SYSTEM_PROMPT = """
            你是 AI 学业规划助手【学途】，当前为日常对话模式。请简洁自然地回答用户的问题。
            【回答规范】
            1. 无法生成或发送文件，所有内容直接在回答中展示。
            2. 禁止虚假操作叙述：严禁声称"正在执行/已完成/已导入"任何操作。
            3. 严禁编造链接或 URL；无法验证链接真实性时改用文字描述。
            4. 记忆时效性：【用户历史学习特征】中带时间标注（如"3周前"）的条目越久远可信度越低，
               与用户当前表述矛盾时以当前说法为准，严禁把过时记忆当作当前事实。
            若用户的问题实际需要操作日历、生成学习计划、安排复习或联网搜索，
            请友好提示用户明确表达需求，例如：「帮我明天下午3点添加一个背单词任务」「帮我制定高数复习计划」。
            """;

    /**
     * 带短时记忆 + 长时记忆召回的多轮对话
     */
    public String chat(Long sessionId, String userMessage) {
        return chat(sessionId, userMessage, false);
    }

    /**
     * 带短时记忆 + 长时记忆召回的多轮对话（支持联网开关）
     */
    public String chat(Long sessionId, String userMessage, boolean webSearch) {
        // 获取用户ID
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        // 1. 获取历史消息列表（优先Redis，降级MySQL）
        List<Map<String, String>> history = loadHistory(sessionId);

        // 2. 构建系统提示词（含长时记忆召回，联网提示）
        String systemPrompt = buildSystemPrompt(userId, sessionId, userMessage);
        if (webSearch) {
            systemPrompt += WEBSEARCH_HINT;
        }

        // 3. 构建 LangChain4j 消息列表
        List<ChatMessage> aiMessages = new ArrayList<>();
        aiMessages.add(SystemMessage.from(systemPrompt));
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("assistant".equals(role)) {
                aiMessages.add(AiMessage.from(content));
            } else if ("user".equals(role)) {
                aiMessages.add(UserMessage.from(content));
            }
            // 其他角色（tool_call/tool_result）已在上游过滤，跳过
        }
        aiMessages.add(UserMessage.from(userMessage));

        // 4. 调用大模型（带工具循环 + 限流重试，设置线程上下文以便工具方法持久化消息）
        ToolContextHolder.set(sessionId, userId, webSearch);
        String reply;
        try {
            reply = callWithRateRetry(() -> chatWithTools(aiMessages));
        } finally {
            ToolContextHolder.clear();
        }
        if (reply == null) {
            // LLM 失败：用户消息已由 Controller 写入 MySQL，这里把失败占位回复同步写入 Redis，保持两份历史一致
            saveFailureHistory(sessionId, userMessage, "抱歉，当前服务繁忙，请稍后再试");
            return "抱歉，当前服务繁忙，请稍后再试";
        }

        // 5. 从回复中提取内联记忆（减少独立 LLM 调用），返回干净回复
        String cleanReply = processInlineMemory(userId, reply);

        // 6. 追加本轮消息到历史（存干净回复），截断为最近20轮（40条），写回Redis
        history.add(Map.of("role", "user", "content", userMessage));
        history.add(Map.of("role", "assistant", "content", cleanReply));
        if (history.size() > MAX_ROUNDS * 2) {
            history = history.subList(history.size() - MAX_ROUNDS * 2, history.size());
        }
        saveHistory(sessionId, history);

        return cleanReply;
    }

    /**
     * SSE 流式对话（真流式）：StreamingChatModel 的 token 边生成边推给前端，
     * 首 token 毫秒级到达，不再等整段生成完。调用方收集完整响应做记忆提取和缓存写入。
     * 流式调用失败时降级为阻塞 chat + 分块模拟流式（保底）。
     */
    public Flux<String> chatStream(Long sessionId, String userMessage, boolean webSearch) {
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        List<Map<String, String>> history = loadHistory(sessionId);
        String systemPrompt = buildStreamSystemPrompt(userId, sessionId, userMessage, webSearch);

        List<ChatMessage> aiMessages = new ArrayList<>();
        aiMessages.add(SystemMessage.from(systemPrompt));
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("assistant".equals(role)) {
                aiMessages.add(AiMessage.from(content));
            } else if ("user".equals(role)) {
                aiMessages.add(UserMessage.from(content));
            }
            // 其他角色（tool_call/tool_result）已在上游过滤，跳过
        }
        aiMessages.add(UserMessage.from(userMessage));

        ToolContextHolder.set(sessionId, userId, webSearch);
        try {
            // 真流式：LLM token 边生成边推；异常降级为阻塞重试后分块模拟流式
            return streamTokens(aiMessages)
                    .doFinally(signal -> ToolContextHolder.clear())
                    .onErrorResume(e -> {
                        log.warn("流式对话调用失败，降级阻塞重试: {}", e.getMessage());
                        return fallbackStream(aiMessages);
                    });
        } catch (Exception e) {
            ToolContextHolder.clear();
            log.warn("流式对话启动失败: {}", e.getMessage());
            return Flux.just("抱歉，当前服务繁忙，请稍后再试");
        }
    }

    /** 把 StreamingChatModel 回调式 API 转换为 Flux<String>（token 逐个推） */
    private Flux<String> streamTokens(List<ChatMessage> messages) {
        return Flux.create(sink -> streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial != null && !partial.isEmpty()) {
                    sink.next(partial);
                }
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                if (partialResponse != null && partialResponse.text() != null && !partialResponse.text().isEmpty()) {
                    sink.next(partialResponse.text());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                sink.complete();
            }

            @Override
            public void onError(Throwable error) {
                sink.error(error);
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    /** 降级保底：流式失败时用阻塞模型生成整段，分块模拟流式发出 */
    private Flux<String> fallbackStream(List<ChatMessage> messages) {
        try {
            String reply = callWithRateRetry(() -> chatNoTools(messages));
            if (reply == null || reply.isBlank()) {
                reply = "抱歉，当前服务繁忙，请稍后再试";
            }
            return chunked(reply);
        } catch (Exception ex) {
            log.error("降级阻塞调用失败: {}", ex.getMessage());
            return Flux.just("抱歉，当前服务繁忙，请稍后再试");
        }
    }

    /** 流式优先 + 工具兜底：先真流式生成（带工具规格，秒出首字）；
     *  若模型在流式中请求调用工具（工具场景，通常不输出文本），
     *  则丢弃流式结果，降级为阻塞工具循环执行后分块模拟流式输出。 */
    private Flux<String> streamWithTools(List<ChatMessage> messages) {
        return Flux.<String>create(sink -> {
            List<ToolSpecification> specs = toolCallExecutor.specifications();
            streamingChatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs)
                    .build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partial) {
                    if (partial != null && !partial.isEmpty()) {
                        sink.next(partial);
                    }
                }

                @Override
                public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                    if (partialResponse != null && partialResponse.text() != null && !partialResponse.text().isEmpty()) {
                        sink.next(partialResponse.text());
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    if (response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests()) {
                        sink.error(new ToolCallDetectedException());
                    } else {
                        sink.complete();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    sink.error(error);
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER)
        .onErrorResume(e -> {
            if (e instanceof ToolCallDetectedException) {
                log.info("流式响应请求调用工具，降级为阻塞工具循环");
                try {
                    return chunked(callWithRateRetry(() -> chatWithTools(messages)));
                } catch (Exception ex) {
                    log.warn("工具循环调用失败: {}", ex.getMessage());
                    return Flux.just("抱歉，当前服务繁忙，请稍后再试");
                }
            }
            log.warn("流式调用失败: {}", e.getMessage());
            return Flux.just("抱歉，当前服务繁忙，请稍后再试");
        });
    }

    /** 标记流式响应中检测到工具调用请求（触发降级为阻塞工具循环） */
    private static class ToolCallDetectedException extends RuntimeException {
        public ToolCallDetectedException() {
            super("streaming response contains tool calls");
        }
    }

    /**
     * 带工具调用的 SSE 流式对话（工具类消息专用）：
     * 工具多轮执行在服务端内部完成（阻塞 chat + 手动工具循环），
     * 拿到完整回复后分块模拟流式输出，前端无需等待整段生成完。
     */
    public Flux<String> chatStreamWithTools(Long sessionId, String userMessage, boolean webSearch) {
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        List<Map<String, String>> history = loadHistory(sessionId);

        // 路由 Agent：规则优先判 SIMPLE/TOOL，判不准时 LLM 兜底，失败默认 TOOL（= 现状行为）
        List<String> recentTurns = history.subList(Math.max(0, history.size() - 4), history.size()).stream()
                .map(m -> ("assistant".equals(m.get("role")) ? "AI：" : "用户：") + m.get("content"))
                .collect(Collectors.toList());
        RouteDecision decision = chatRouter.route(recentTurns, userMessage);

        String systemPrompt;
        if (decision.route() == ChatRoute.SIMPLE) {
            systemPrompt = buildSimpleSystemPrompt(userId, sessionId, userMessage);
        } else {
            systemPrompt = buildSystemPrompt(userId, sessionId, userMessage);
            if (webSearch) {
                systemPrompt += WEBSEARCH_HINT;
            }
        }

        List<ChatMessage> aiMessages = new ArrayList<>();
        aiMessages.add(SystemMessage.from(systemPrompt));
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("assistant".equals(role)) {
                aiMessages.add(AiMessage.from(content));
            } else if ("user".equals(role)) {
                aiMessages.add(UserMessage.from(content));
            }
            // 其他角色（tool_call/tool_result）已在上游过滤，跳过
        }
        aiMessages.add(UserMessage.from(userMessage));

        ToolContextHolder.set(sessionId, userId, webSearch);

        // SIMPLE：精简 prompt + 真流式（不带工具规格，省 token 且无工具误触发）
        if (decision.route() == ChatRoute.SIMPLE) {
            return streamTokens(aiMessages)
                    .doFinally(signal -> ToolContextHolder.clear())
                    .onErrorResume(e -> {
                        log.warn("SIMPLE路由流式调用失败，降级阻塞重试: {}", e.getMessage());
                        return fallbackStream(aiMessages);
                    });
        }

        // TOOL：真流式优先（带工具规格）：秒出首字；模型请求工具时自动降级为阻塞工具循环
        return streamWithTools(aiMessages)
                .doFinally(signal -> ToolContextHolder.clear());
    }

    /** 无工具降级（阻塞 call）：替换 system 为无工具版，其余消息原样保留 */
    private String fallbackCallNoTools(List<ChatMessage> originalMessages, Long userId, Long sessionId, String userMessage) {
        try {
            String fallbackPrompt = buildStreamSystemPrompt(userId, sessionId, userMessage, false);
            fallbackPrompt += "\n\n【重要】当前工具与联网搜索均不可用，涉及实时数据（价格、新闻、政策等）时严禁编造，请如实告知用户暂时无法获取实时数据。";
            List<ChatMessage> fallbackMessages = new ArrayList<>();
            fallbackMessages.add(SystemMessage.from(fallbackPrompt));
            for (ChatMessage m : originalMessages) {
                if (m instanceof SystemMessage) continue;
                fallbackMessages.add(m);
            }
            return callWithRateRetry(() -> chatNoTools(fallbackMessages));
        } catch (Exception ex) {
            log.error("无工具降级重试失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 带限流退避的阻塞调用：DeepSeek 高峰期会返回 429（code 50609 System is too busy now），
     * 此类错误按 1s/2s/4s 退避最多重试 3 次；其余异常不重试直接抛出。
     * 此外，模型调用成功但返回空内容（DeepSeek 偶发）也视为可重试条件，同样退避重试，
     * 避免把"成功但空回复"误报为服务繁忙。
     */
    private String callWithRateRetry(Supplier<String> call) {
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
                if (isRateLimited(msg) && attempt < 2) {
                    // SiliconFlow 高峰 "System is too busy" 限流窗口常达数秒~数十秒，
                    // 退避加大到 2s/4s 提高错开限流窗口的概率
                    int waitMs = 2000 * (attempt + 1);
                    log.warn("模型限流(429)，等待{}ms后第{}次重试", waitMs, attempt + 2);
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

    /** 无工具阻塞调用：单轮 chat，返回模型文本回复（DeepSeek 偶发空内容返回 null） */
    private String chatNoTools(List<ChatMessage> messages) {
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(messages)
                .build());
        AiMessage ai = response.aiMessage();
        return ai != null ? ai.text() : null;
    }

    /**
     * 带工具阻塞调用：手动工具循环（LangChain4j 无 AiServices 时的手写等价物）。
     * 每轮 chat 若模型请求调用工具，则通过 ToolCallExecutor 依次执行并把结果以
     * ToolExecutionResultMessage 追加回消息列表，再进入下一轮；直到模型直接输出
     * 文本回复或超过 MAX_TOOL_ROUNDS 上限。
     */
    private String chatWithTools(List<ChatMessage> messages) {
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
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(current)
                    .toolSpecifications(specs)
                    .build());
            AiMessage aiMessage = response.aiMessage();
            if (aiMessage == null) {
                return null;
            }
            if (aiMessage.hasToolExecutionRequests()) {
                current.add(aiMessage);
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    String result = toolCallExecutor.execute(request);
                    // 评审Agent：规则评审（防幻觉/防跨用户泄露）+ LLM 语义评审（只读工具），失败降级直通
                    result = reviewAgent.review(userMessage, request, result);
                    current.add(ToolExecutionResultMessage.from(request, result));
                }
            } else {
                return aiMessage.text();
            }
        }
        log.warn("工具循环超过{}轮仍未收敛，返回最后一轮内容", MAX_TOOL_ROUNDS);
        return "抱歉，工具调用次数过多，请重试或简化请求";
    }

    /** 将完整回复按小块切分，块间加固定延迟模拟逐字输出（前端无需改动）。
     *  延迟是关键：零间隔的 Flux 会被浏览器/JS 事件循环一次性吞掉，视觉上变成"整段输出"。
     *  块 4 字符 + 10ms 间隔是"流式感"与"总时长"的折中：500 字回复约多花 1.2s。 */
    private static final Duration CHUNK_INTERVAL = Duration.ofMillis(10);
    private static final int CHUNK_SIZE = 4;

    private Flux<String> chunked(String reply) {
        List<String> chunks = new ArrayList<>();
        if (reply != null && !reply.isEmpty()) {
            for (int i = 0; i < reply.length(); i += CHUNK_SIZE) {
                chunks.add(reply.substring(i, Math.min(i + CHUNK_SIZE, reply.length())));
            }
        }
        if (chunks.isEmpty()) {
            chunks.add("抱歉，当前服务繁忙，请稍后再试");
        }
        return Flux.fromIterable(chunks)
                .concatMap(chunk -> Mono.just(chunk).delayElement(CHUNK_INTERVAL));
    }

    /**
     * 带工具调用的重新生成（流式）：基于最后一条用户消息重新回答。
     * 用户消息不重复落库，工具多轮在服务端内部完成，最终回答阶段流式输出。
     */
    public Flux<String> regenerateStream(Long sessionId, boolean webSearch) {
        Long lastUserMessageId = messageMapper.getLastUserMessageId(sessionId);
        if (lastUserMessageId == null) {
            return Flux.just("没有可重新生成的消息");
        }
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;

        List<Map<String, String>> history = loadHistory(sessionId);
        String lastUserContent = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).get("role"))) {
                lastUserContent = history.get(i).get("content");
                // 保留用户消息本身，截断其后的历史（旧回复）
                history = history.subList(0, i + 1);
                break;
            }
        }
        if (lastUserContent == null) {
            return Flux.just("没有可重新生成的消息");
        }

        // 保留 MySQL 中的旧版本回复（前端可切换查看），Redis 历史只保留最新版本
        saveHistory(sessionId, history);

        String systemPrompt = buildSystemPrompt(userId, sessionId, lastUserContent);
        if (webSearch) {
            systemPrompt += WEBSEARCH_HINT;
        }

        List<ChatMessage> aiMessages = new ArrayList<>();
        aiMessages.add(SystemMessage.from(systemPrompt));
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("assistant".equals(role)) {
                aiMessages.add(AiMessage.from(content));
            } else if ("user".equals(role)) {
                aiMessages.add(UserMessage.from(content));
            }
            // 其他角色（tool_call/tool_result）已在上游过滤，跳过
        }
        aiMessages.add(UserMessage.from(lastUserContent));

        ToolContextHolder.set(sessionId, userId, webSearch);

        // 真流式优先（带工具规格）：秒出首字；模型请求工具时自动降级为阻塞工具循环
        return streamWithTools(aiMessages)
                .doFinally(signal -> ToolContextHolder.clear());
    }

    /**
     * 流式重新生成后的收尾：提取内联记忆、更新历史缓存。
     * 用户消息已存在于历史中（regenerateStream 保留），不重复追加。
     */
    public String finishRegenerate(Long sessionId, Long userId, String fullReply) {
        String cleanReply = processInlineMemory(userId, fullReply);
        List<Map<String, String>> history = loadHistory(sessionId);
        history.add(Map.of("role", "assistant", "content", cleanReply));
        if (history.size() > MAX_ROUNDS * 2) {
            history = history.subList(history.size() - MAX_ROUNDS * 2, history.size());
        }
        saveHistory(sessionId, history);
        return cleanReply;
    }

    /**
     * 流式响应后的收尾：提取内联记忆、更新历史缓存
     */
    public String finishStream(Long sessionId, Long userId, String userMessage, String fullReply) {
        String cleanReply = processInlineMemory(userId, fullReply);
        List<Map<String, String>> history = loadHistory(sessionId);
        history.add(Map.of("role", "user", "content", userMessage));
        history.add(Map.of("role", "assistant", "content", cleanReply));
        if (history.size() > MAX_ROUNDS * 2) {
            history = history.subList(history.size() - MAX_ROUNDS * 2, history.size());
        }
        saveHistory(sessionId, history);
        return cleanReply;
    }

    /**
     * LLM 调用失败时记录占位回复，保持 Redis 与 MySQL 历史一致。
     * 阻塞端点由 Controller 负责把用户消息和占位回复落库 MySQL，这里只同步 Redis；
     * 流式端点由 Controller 落库 MySQL 后调用本方法。
     */
    public void saveFailureHistory(Long sessionId, String userMessage, String placeholder) {
        saveFailureHistory(sessionId, userMessage, placeholder, true);
    }

    /**
     * 重新生成失败时：只追加失败占位回复（用户消息已在历史中，不重复追加）
     */
    public void saveRegenerateFailureHistory(Long sessionId, String placeholder) {
        saveFailureHistory(sessionId, null, placeholder, false);
    }

    private void saveFailureHistory(Long sessionId, String userMessage, String placeholder, boolean includeUser) {
        try {
            List<Map<String, String>> history = loadHistory(sessionId);
            if (includeUser && userMessage != null) {
                history.add(Map.of("role", "user", "content", userMessage));
            }
            history.add(Map.of("role", "assistant", "content", placeholder));
            if (history.size() > MAX_ROUNDS * 2) {
                history = history.subList(history.size() - MAX_ROUNDS * 2, history.size());
            }
            saveHistory(sessionId, history);
        } catch (Exception e) {
            log.warn("写入失败占位历史失败: {}", e.getMessage());
        }
    }

    /**
     * 构建系统提示词，含当前日期 + 长时记忆召回
     */
    private String buildSystemPrompt(Long userId, Long sessionId, String userMessage) {
        String dateRef = buildDateReference();
        String datedPrompt = TOOL_SYSTEM_PROMPT + "\n\n" + dateRef;

        if (userId == null) {
            return datedPrompt;
        }

        // 今日日历 + 长时记忆/档案，与流式端共用同一套上下文
        return datedPrompt + buildUserContext(userId, sessionId, userMessage);
    }

    /**
     * 生成日期参考表，帮 LLM 正确映射"下周一"等相对日期
     */
    private String buildDateReference() {
        LocalDate today = LocalDate.now();
        DayOfWeek dow = today.getDayOfWeek();
        String[] weekDays = {"周一","周二","周三","周四","周五","周六","周日"};

        // 本周一
        LocalDate thisMonday = today.minusDays(dow.getValue() - DayOfWeek.MONDAY.getValue());
        // 下周一
        LocalDate nextMonday = thisMonday.plusDays(7);

        StringBuilder sb = new StringBuilder();
        sb.append("【日期参考】今天是 ").append(today).append("（").append(weekDays[dow.getValue()-1]).append("）。\n");
        sb.append("常用相对日期对应如下（直接用，不要自己算）：\n");

        // 明天/后天
        sb.append(String.format("  - 明天 = %s / 后天 = %s\n",
                today.plusDays(1), today.plusDays(2)));

        // 下周一到周日
        for (int i = 0; i < 7; i++) {
            LocalDate d = nextMonday.plusDays(i);
            sb.append(String.format("  - 下%s = %s\n", weekDays[i], d));
        }

        // 本周剩余 + 下周
        sb.append(String.format("  - 本周剩余 = %s 至 %s\n", today, thisMonday.plusDays(6)));
        sb.append(String.format("  - 下周 = %s 至 %s\n", nextMonday, nextMonday.plusDays(6)));

        return sb.toString();
    }

    /**
     * 流式专用提示词：支持日历工具调用，预注入日历数据
     */
    private String buildStreamSystemPrompt(Long userId, Long sessionId, String userMessage, boolean webSearch) {
        String dateRef = buildDateReference();

        String prompt = STREAM_SYSTEM_PROMPT + "\n\n" + dateRef;

        if (userId != null) {
            // 今日日历 + 长时记忆/档案，与阻塞端共用同一套上下文
            prompt += buildUserContext(userId, sessionId, userMessage);
        }

        if (webSearch) {
            prompt += WEBSEARCH_HINT;
        }

        return prompt;
    }

    /**
     * SIMPLE 路由精简提示词：角色规范 + 日期参考 + 用户上下文（昵称/日历/记忆召回保留，
     * 个性化是核心卖点；省掉的是工具说明书和工具铁律段）。
     */
    private String buildSimpleSystemPrompt(Long userId, Long sessionId, String userMessage) {
        String prompt = SIMPLE_SYSTEM_PROMPT + "\n\n" + buildDateReference();
        if (userId != null) {
            prompt += buildUserContext(userId, sessionId, userMessage);
        }
        return prompt;
    }

    /**
     * 构建用户上下文：今日日历 + 长时记忆召回（或档案兜底）。
     * 阻塞端与流式端共用，保证两个入口对 LLM 的上下文一致。
     */
    private String buildUserContext(Long userId, Long sessionId, String userMessage) {
        StringBuilder sb = new StringBuilder();

        // 用户昵称：AI 用昵称称呼用户（个人设置中可修改，以 sys_user.name 为权威来源）
        String userNickname = null;
        try {
            SysUser user = userMapper.findById(userId);
            if (user != null && user.getName() != null && !user.getName().isBlank()) {
                userNickname = user.getName();
                sb.append("\n\n【用户昵称】").append(userNickname)
                  .append("。对话中请用「").append(userNickname)
                  .append("」适当称呼该用户（每段回答开头称呼一次即可，不要过度重复）；"
                          + "若用户问\"我叫什么/我的名字/你认识我吗\"，直接回答该昵称。");
            }
        } catch (Exception e) {
            log.warn("注入用户昵称失败: {}", e.getMessage());
        }

        // 预查今日日历
        try {
            var todayEvents = calendarService.getEventsByDateRange(userId, java.time.LocalDate.now(), java.time.LocalDate.now());
            if (!todayEvents.isEmpty()) {
                sb.append("\n\n【今日日历】\n");
                for (var ev : todayEvents) {
                    sb.append("- ").append(ev.getTitle())
                      .append("（").append(ev.getEventType() != null ? ev.getEventType() : "task").append("）\n");
                }
            }
        } catch (Exception e) {
            log.warn("注入今日日历失败: {}", e.getMessage());
        }

        // 已完成的学习任务：直接从数据源注入（不依赖向量召回，AI 一定知道用户完成过什么；
        // 取消勾选后立即从列表移除，与记忆同步）
        try {
            var completedEvents = calendarService.findCompletedEvents(userId, 10);
            if (!completedEvents.isEmpty()) {
                sb.append("\n\n【已完成的学习任务】\n");
                for (var ev : completedEvents) {
                    sb.append("- ").append(ev.getTitle())
                      .append("（完成于 ").append(ev.getEventDate()).append("）\n");
                }
            }
        } catch (Exception e) {
            log.warn("注入已完成任务失败: {}", e.getMessage());
        }

        // 学习完成统计：给 AI 量化视角，便于分析学习情况
        try {
            Map<String, Integer> stats = calendarService.completionStats(userId);
            int total = stats.getOrDefault("totalCompleted", 0);
            int week = stats.getOrDefault("weekCompleted", 0);
            int pending = stats.getOrDefault("pending", 0);
            if (total > 0 || pending > 0) {
                sb.append("\n\n【学习完成统计】累计完成 ").append(total)
                  .append(" 个学习任务；近7天完成 ").append(week)
                  .append(" 个；当前未完成 ").append(pending)
                  .append(" 个。\n（注：勾选完成仅代表任务已做，不代表已掌握；"
                          + "掌握程度以用户对话中的自我评价为准。）");
            }
        } catch (Exception e) {
            log.warn("注入完成统计失败: {}", e.getMessage());
        }

        // 长时记忆召回
        List<String> recalledMemories = recallMemories(userId, userMessage);
        // 昵称以 sys_user.name 为准，过滤记忆中的旧昵称条目，避免与新昵称冲突
        if (userNickname != null) {
            recalledMemories.removeIf(m -> m.startsWith("用户昵称-"));
        }
        // 过滤"已完成任务"记忆：完成情况已由【已完成的学习任务】实时注入，避免重复
        recalledMemories.removeIf(m -> m.contains("已完成"));
        // 时间衰减标注：从 MySQL 取每条记忆的创建时间，标注相对时效（如"3周前"），
        // 配合规则6让 AI 对久远记忆降权、主动确认是否仍有效
        Map<String, String> timeTags = memoryTimeTags(userId);
        if (!recalledMemories.isEmpty()) {
            String tagged = recalledMemories.stream()
                    .map(m -> m + (timeTags.containsKey(m) ? "（" + timeTags.get(m) + "）" : ""))
                    .collect(Collectors.joining("\n- ", "- ", ""));
            sb.append("\n\n【用户历史学习特征】\n").append(tagged);
        } else {
            // 兜底：使用学业档案作为基础画像
            String profileFallback = buildProfileFallback(userId);
            if (!profileFallback.isEmpty()) {
                sb.append("\n\n【用户学习档案】\n").append(profileFallback);
            }
        }

        // 本会话启用的参考资料（用户从资料库选择 / 临时上传挂到会话的）：资料参考以会话为准，
        // 只有挂载到当前会话的资料才会注入，移除会话关联后 AI 不再参考
        if (sessionId != null) {
            try {
                var rels = chatSessionMaterialMapper.findBySessionId(sessionId);
                if (!rels.isEmpty()) {
                    StringBuilder matSb = new StringBuilder();
                    int count = 0;
                    for (var rel : rels) {
                        StudyMaterial m = studyMaterialMapper.findById(rel.getMaterialId());
                        if (m == null || !m.getUserId().equals(userId) || m.getContentText() == null) continue;
                        if (count >= 3) break; // 每会话最多注入 3 份
                        String content = m.getContentText().trim();
                        if (content.isEmpty()) continue;
                        matSb.append("【资料").append(count + 1).append("：").append(m.getFileName()).append("】\n")
                             .append(content.length() > 800 ? content.substring(0, 800) + "…（后略）" : content)
                             .append("\n");
                        count++;
                    }
                    if (count > 0) {
                        sb.append("\n\n【本会话参考资料】（用户明确选择在当前会话中参考的资料，涉及相关内容时必须优先于其他记忆参考）\n")
                          .append(matSb);
                    }
                }
            } catch (Exception e) {
                log.warn("注入会话参考资料失败: {}", e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * 从 Chroma 向量库召回相关记忆
     */
    private List<String> recallMemories(Long userId, String userMessage) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(userMessage).content();
            Filter filter = MetadataFilterBuilder.metadataKey("userId")
                    .isEqualTo(String.valueOf(userId));
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                            EmbeddingSearchRequest.builder()
                                    .queryEmbedding(queryEmbedding)
                                    .maxResults(RECALL_TOP_K)
                                    .minScore(RECALL_THRESHOLD)
                                    .filter(filter)
                                    .build())
                    .matches();
            return matches.stream()
                    .map(m -> m.embedded() != null ? m.embedded().text() : null)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("向量库召回失败，降级档案兜底: {}", e.getMessage());
            // 注意：必须返回可变列表，调用方会对结果做 removeIf 过滤
            return new ArrayList<>();
        }
    }

    /**
     * 构建 记忆文本 → 相对时效标注 的映射（时间衰减展示，如"3周前"）
     */
    private Map<String, String> memoryTimeTags(Long userId) {
        Map<String, String> tags = new HashMap<>();
        try {
            List<MemoryRecord> records = memoryRecordMapper.findByUserId(userId);
            LocalDateTime now = LocalDateTime.now();
            for (MemoryRecord r : records) {
                if (r.getCreateTime() == null) continue;
                tags.putIfAbsent(r.getMemoryText(), relativeTime(r.getCreateTime(), now));
            }
        } catch (Exception e) {
            log.warn("记忆时间标注失败: {}", e.getMessage());
        }
        return tags;
    }

    /** 相对时间表达 */
    private String relativeTime(LocalDateTime t, LocalDateTime now) {
        long days = Duration.between(t, now).toDays();
        if (days < 1) return "今天";
        if (days == 1) return "昨天";
        if (days < 7) return days + "天前";
        if (days < 30) return (days / 7) + "周前";
        if (days < 365) return (days / 30) + "个月前";
        return (days / 365) + "年前";
    }

    /**
     * 档案兜底：将用户学业档案格式化为基础画像
     */
    private String buildProfileFallback(Long userId) {
        try {
            StudentProfile profile = profileMapper.findByUserId(userId);
            if (profile == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (profile.getWeakSubjects() != null && !profile.getWeakSubjects().isBlank()) {
                sb.append("薄弱科目: ").append(profile.getWeakSubjects()).append("; ");
            }
            if (profile.getExamPlans() != null && !profile.getExamPlans().isBlank()) {
                sb.append("考试计划: ").append(profile.getExamPlans()).append("; ");
            }
            if (profile.getStudyGoals() != null && !profile.getStudyGoals().isBlank()) {
                sb.append("学习目标: ").append(profile.getStudyGoals()).append("; ");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("读取档案兜底失败: {}", e.getMessage());
            return "";
        }
    }

    // ========== 会话参考资料（选择资料库文件挂到会话，AI 对话时参考） ==========

    /** 当前会话已启用的参考资料列表（元信息，供前端展示） */
    public List<Map<String, Object>> listSessionMaterials(Long userId, Long sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatSessionMaterial rel : chatSessionMaterialMapper.findBySessionId(sessionId)) {
            StudyMaterial m = studyMaterialMapper.findById(rel.getMaterialId());
            if (m == null || !m.getUserId().equals(userId)) continue;
            out.add(Map.of(
                    "materialId", m.getMaterialId(),
                    "fileName", m.getFileName(),
                    "fileSize", m.getFileSize()
            ));
        }
        return out;
    }

    /** 把资料库文件挂到当前会话（重复挂载自动忽略），返回是否新增 */
    public boolean addSessionMaterial(Long userId, Long sessionId, Long materialId) {
        StudyMaterial m = studyMaterialMapper.findById(materialId);
        if (m == null || !m.getUserId().equals(userId)) {
            throw new IllegalArgumentException("资料不存在");
        }
        ChatSessionMaterial rel = new ChatSessionMaterial();
        rel.setSessionId(sessionId);
        rel.setMaterialId(materialId);
        return chatSessionMaterialMapper.insert(rel) > 0;
    }

    /** 从会话移除参考资料；临时上传的资料（is_temp=1）一并物理删除 */
    public void removeSessionMaterial(Long userId, Long sessionId, Long materialId) {
        StudyMaterial m = studyMaterialMapper.findById(materialId);
        if (m != null && !m.getUserId().equals(userId)) {
            throw new IllegalArgumentException("资料不存在");
        }
        chatSessionMaterialMapper.delete(sessionId, materialId);
        // 临时上传：只在当前会话用，移除后直接清理，避免残留
        if (m != null && Integer.valueOf(1).equals(m.getIsTemp())) {
            try {
                studyMaterialMapper.deleteById(materialId, userId);
            } catch (Exception e) {
                log.warn("清理临时资料{}失败: {}", materialId, e.getMessage());
            }
        }
    }

    // ========== 用户上传学习资料（轻量版：文本注入，不走向量库） ==========

    private static final String UPLOAD_PREFIX = "【上传资料】";
    private static final int UPLOAD_MAX_FILE_SIZE = 2 * 1024 * 1024;   // 2MB
    private static final int UPLOAD_MAX_CHARS = 5000;                  // 单文件最多保留字符数
    private static final int UPLOAD_MAX_INJECT = 3;                    // 每次对话最多注入条数

    /**
     * 上传学习资料（.txt/.md/.csv）：读取纯文本写入长时记忆（带【上传资料】前缀），
     * 后续对话自动注入供 AI 参考。
     */
    public Map<String, Object> uploadFile(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件为空");
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) filename = "unnamed.txt";
        String lower = filename.toLowerCase();
        if (!(lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".csv"))) {
            throw new IllegalArgumentException("仅支持 .txt / .md / .csv 文本文件");
        }
        if (file.getSize() > UPLOAD_MAX_FILE_SIZE) throw new IllegalArgumentException("文件不能超过 2MB");
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("文件读取失败: " + e.getMessage());
        }
        content = content.trim();
        if (content.isEmpty()) throw new IllegalArgumentException("文件内容为空");
        if (content.length() > UPLOAD_MAX_CHARS) {
            content = content.substring(0, UPLOAD_MAX_CHARS) + "\n…（内容过长，已截断）";
        }
        MemoryRecord record = new MemoryRecord();
        record.setUserId(userId);
        record.setMemoryText(UPLOAD_PREFIX + filename + "\n" + content);
        // vector_id 列非空：上传资料不走向量库，用唯一占位符（避免与 Chroma 真实 id 冲突）
        record.setVectorId("upload-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 100000));
        memoryRecordMapper.insert(record);
        log.info("用户{}上传学习资料: {} ({}字符)", userId, filename, content.length());
        return Map.of("fileName", filename, "recordId", record.getRecordId(), "chars", content.length());
    }

    /** 查询用户已上传的资料列表（按最新在前） */
    public List<Map<String, Object>> listUploadedFiles(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MemoryRecord r : memoryRecordMapper.findByUserId(userId)) {
            if (r.getMemoryText() == null || !r.getMemoryText().startsWith(UPLOAD_PREFIX)) continue;
            String rest = r.getMemoryText().substring(UPLOAD_PREFIX.length());
            String name = rest;
            int nl = rest.indexOf('\n');
            if (nl > 0) name = rest.substring(0, nl);
            out.add(Map.of("recordId", r.getRecordId(), "fileName", name,
                    "createTime", r.getCreateTime() != null ? r.getCreateTime().toString() : ""));
        }
        out.sort((a, b) -> String.valueOf(b.get("createTime")).compareTo(String.valueOf(a.get("createTime"))));
        return out;
    }

    /** 删除一条上传资料（仅限本人且确属上传资料） */
    public int deleteUploadedFile(Long userId, Long recordId) {
        MemoryRecord r = memoryRecordMapper.findById(recordId);
        if (r == null || !r.getUserId().equals(userId)
                || r.getMemoryText() == null || !r.getMemoryText().startsWith(UPLOAD_PREFIX)) {
            return 0;
        }
        return memoryRecordMapper.deleteByIds(List.of(recordId));
    }

    /** 取最新 N 条上传资料原文（去前缀），供 buildUserContext 注入 */
    private List<String> listUploadedTexts(Long userId) {
        List<String> all = new ArrayList<>();
        for (MemoryRecord r : memoryRecordMapper.findByUserId(userId)) {
            if (r.getMemoryText() == null || !r.getMemoryText().startsWith(UPLOAD_PREFIX)) continue;
            all.add(r.getMemoryText().substring(UPLOAD_PREFIX.length()));
        }
        java.util.Collections.reverse(all); // findByUserId 无排序，倒序后最新在前
        return all.size() > UPLOAD_MAX_INJECT ? all.subList(0, UPLOAD_MAX_INJECT) : all;
    }

    // ========== 以下为短时记忆缓存相关方法（阶段2.2） ==========

    private List<Map<String, String>> loadHistory(Long sessionId) {
        String key = HISTORY_KEY_PREFIX + sessionId;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
            }
        } catch (Exception e) {
            log.warn("Redis读取历史失败，降级MySQL: {}", e.getMessage());
        }
        return loadFromMySQL(sessionId);
    }

    private void saveHistory(Long sessionId, List<Map<String, String>> history) {
        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(
                    HISTORY_KEY_PREFIX + sessionId,
                    json,
                    CACHE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("Redis写入历史失败: {}", e.getMessage());
        }
    }

    private List<Map<String, String>> loadFromMySQL(Long sessionId) {
        List<com.studentagent.studentagent.entity.ChatMessage> messages = messageMapper.findBySessionId(sessionId);
        List<Map<String, String>> history = new ArrayList<>();
        // 每轮（一条 user 消息）可能有多条 assistant 回复（重新生成时保留的旧版本），
        // 回放历史时每轮只保留最后一条（最新版本），旧版本仅用于前端切换展示
        com.studentagent.studentagent.entity.ChatMessage pendingAssistant = null;
        for (com.studentagent.studentagent.entity.ChatMessage msg : messages) {
            String role = msg.getRole();
            // 工具调用/结果为模型内部过程消息，回放历史时过滤，避免污染用户上下文
            if ("tool_call".equals(role) || "tool_result".equals(role)) continue;
            if ("assistant".equals(role)) {
                pendingAssistant = msg;
                continue;
            }
            if ("user".equals(role)) {
                if (pendingAssistant != null) {
                    history.add(Map.of("role", "assistant", "content", pendingAssistant.getContent()));
                    pendingAssistant = null;
                }
                history.add(Map.of("role", "user", "content", msg.getContent()));
            }
        }
        if (pendingAssistant != null) {
            history.add(Map.of("role", "assistant", "content", pendingAssistant.getContent()));
        }
        if (history.size() > MAX_ROUNDS * 2) {
            history = history.subList(history.size() - MAX_ROUNDS * 2, history.size());
        }
        saveHistory(sessionId, history);
        return history;
    }

    public void clearHistory(Long sessionId) {
        try {
            redisTemplate.delete(HISTORY_KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("清除Redis历史失败: {}", e.getMessage());
        }
    }

    public void rebuildHistory(Long sessionId) {
        try {
            redisTemplate.delete(HISTORY_KEY_PREFIX + sessionId);
            loadFromMySQL(sessionId);
        } catch (Exception e) {
            log.warn("重建Redis历史失败: {}", e.getMessage());
        }
    }

    public String regenerateReply(Long sessionId) {
        return regenerateReply(sessionId, false);
    }

    public String regenerateReply(Long sessionId, boolean webSearch) {
        Long lastUserMessageId = messageMapper.getLastUserMessageId(sessionId);
        if (lastUserMessageId == null) {
            return "没有可重新生成的消息";
        }

        // 先读取最后一条用户消息内容，截断其后的历史（用户消息本身保留，regenerate 只替换它后面的回复）
        String lastUserContent = null;
        List<Map<String, String>> fullHistory = loadHistory(sessionId);
        for (int i = fullHistory.size() - 1; i >= 0; i--) {
            if ("user".equals(fullHistory.get(i).get("role"))) {
                lastUserContent = fullHistory.get(i).get("content");
                fullHistory = fullHistory.subList(0, i + 1);
                break;
            }
        }
        if (lastUserContent == null) {
            return "没有可重新生成的消息";
        }

        // 保留 MySQL 中的旧版本回复（前端可切换查看），Redis 历史只保留最新版本
        saveHistory(sessionId, fullHistory);

        // 获取用户ID用于记忆召回
        var session = sessionMapper.findById(sessionId);
        Long userId = session != null ? session.getUserId() : null;
        String systemPrompt = buildSystemPrompt(userId, sessionId, lastUserContent);
        if (webSearch) {
            systemPrompt += WEBSEARCH_HINT;
        }

        List<ChatMessage> aiMessages = new ArrayList<>();
        aiMessages.add(SystemMessage.from(systemPrompt));
        for (Map<String, String> msg : fullHistory) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("assistant".equals(role)) {
                aiMessages.add(AiMessage.from(content));
            } else if ("user".equals(role)) {
                aiMessages.add(UserMessage.from(content));
            }
        }
        aiMessages.add(UserMessage.from(lastUserContent));

        // 调用大模型（带工具循环 + 限流重试，设置线程上下文以便工具方法持久化消息）
        ToolContextHolder.set(sessionId, userId, webSearch);
        String reply;
        try {
            reply = callWithRateRetry(() -> chatWithTools(aiMessages));
        } finally {
            ToolContextHolder.clear();
        }
        if (reply == null) {
            // LLM 失败：用户消息仍在 MySQL 中，把失败占位回复同时写入 MySQL + Redis，保持两份历史一致
            com.studentagent.studentagent.entity.ChatMessage failMsg = new com.studentagent.studentagent.entity.ChatMessage();
            failMsg.setSessionId(sessionId);
            failMsg.setRole("assistant");
            failMsg.setContent("抱歉，当前服务繁忙，请稍后再试");
            messageMapper.insert(failMsg);
            saveFailureHistory(sessionId, lastUserContent, "抱歉，当前服务繁忙，请稍后再试");
            return "抱歉，当前服务繁忙，请稍后再试";
        }

        // 提取内联记忆，存干净回复
        String cleanReply = processInlineMemory(userId, reply);

        com.studentagent.studentagent.entity.ChatMessage aiMsg = new com.studentagent.studentagent.entity.ChatMessage();
        aiMsg.setSessionId(sessionId);
        aiMsg.setRole("assistant");
        aiMsg.setContent(cleanReply);
        messageMapper.insert(aiMsg);

        fullHistory.add(Map.of("role", "assistant", "content", cleanReply));
        if (fullHistory.size() > MAX_ROUNDS * 2) {
            fullHistory = fullHistory.subList(fullHistory.size() - MAX_ROUNDS * 2, fullHistory.size());
        }
        saveHistory(sessionId, fullHistory);
        return cleanReply;
    }

    /**
     * 异步用 AI 生成会话标题
     */
    @Async("memoryExtractExecutor")
    public void generateTitleAsync(Long sessionId, String firstMessage) {
        try {
            String result = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("用6-10个字概括用户的问题作为对话标题，只输出标题，不要带引号和标点。"),
                            UserMessage.from(firstMessage)))
                    .build())
                    .aiMessage().text();
            if (result != null && !result.isBlank()) {
                String title = result.trim();
                if (title.length() > 20) title = title.substring(0, 20);
                sessionMapper.autoTitle(sessionId, title);
                log.info("AI生成标题: session={}, title={}", sessionId, title);
            }
        } catch (Exception e) {
            // 失败就用首条消息前15字兜底
            String fallback = firstMessage.length() > 15 ? firstMessage.substring(0, 15) : firstMessage;
            sessionMapper.autoTitle(sessionId, fallback);
            log.debug("AI标题生成失败，使用兜底: {}", fallback);
        }
    }

    /**
     * 从 LLM 回复中解析 ---MEMORY--- 块，调用 MemoryExtractService 去重存储，
     * 异步触发标签生成，返回剥离记忆块后的干净回复。无块则原样返回。
     */
    private String processInlineMemory(Long userId, String reply) {
        if (userId == null || reply == null) {
            return reply != null ? reply : "";
        }

        String marker = "---MEMORY---";
        String endMarker = "---END---";

        int startIdx = reply.indexOf(marker);
        if (startIdx == -1) {
            return reply;
        }

        // 防泄露兜底：即使 AI 只写了 ---MEMORY--- 未写 ---END---（格式漂移/被截断），
        // 也必须把 marker 至末尾整块剥离，绝不把记忆块暴露给用户。
        int endIdx = reply.indexOf(endMarker, startIdx);
        String memBlock;
        String beforeBlock = reply.substring(0, startIdx);
        String afterBlock = "";
        if (endIdx == -1) {
            log.debug("用户{}的回复含---MEMORY---但无---END---，按到末尾剥离防泄露", userId);
            memBlock = reply.substring(startIdx + marker.length()).trim();
        } else {
            memBlock = reply.substring(startIdx + marker.length(), endIdx).trim();
            afterBlock = reply.substring(endIdx + endMarker.length());
        }
        String cleanReply = (beforeBlock + afterBlock).replaceAll("\\n{3,}", "\n\n").trim();

        if (!memBlock.isEmpty() && !memBlock.equals("无")) {
            try {
                int count = memoryExtractService.processAndStore(userId, memBlock);
                if (count > 0) {
                    log.info("用户{}内联记忆提取成功 +{}条，异步触发标签更新", userId, count);
                    profileService.generateTagsAsync(userId);
                }
            } catch (Exception e) {
                log.warn("用户{}内联记忆处理失败: {}", userId, e.getMessage());
            }
        }

        return cleanReply;
    }
}
