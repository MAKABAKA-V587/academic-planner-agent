package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.SessionMapper;
import com.studentagent.studentagent.service.CalendarService;
import com.studentagent.studentagent.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final ChatService chatService;

    @PostMapping("/chat")
    public Result<Map<String, String>> chat(@RequestAttribute Long userId,
                                            @RequestBody Map<String, Object> body) {
        Long sessionId = Long.valueOf(body.get("sessionId").toString());
        String message = body.get("message").toString();
        boolean webSearch = body.get("webSearch") instanceof Boolean b && b;

        // 校验会话归属
        var session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return Result.fail(403, "会话不存在");
        }

        // 保存用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("user");
        userMsg.setContent(message);
        messageMapper.insert(userMsg);

        // 首次对话时用 AI 生成标题（仅当标题为"新对话"且未被用户锁定）
        if ("新对话".equals(session.getTitle()) && !Boolean.TRUE.equals(session.getTitleLocked())) {
            chatService.generateTitleAsync(sessionId, message);
        }
        // 更新活跃时间
        sessionMapper.touch(sessionId);

        // 调用大模型生成回复（带短时记忆上下文 + 工具调用）
        String reply = chatService.chat(sessionId, message, webSearch);

        // 保存 AI 回复
        ChatMessage aiMsg = new ChatMessage();
        aiMsg.setSessionId(sessionId);
        aiMsg.setRole("assistant");
        aiMsg.setContent(reply);
        messageMapper.insert(aiMsg);

        return Result.ok(Map.of("role", "assistant", "content", reply));
    }

    /**
     * 上传学习资料（.txt/.md/.csv）：解析纯文本写入记忆，后续对话自动注入供 AI 参考
     */
    @PostMapping("/chat/upload-file")
    public Result<Map<String, Object>> uploadFile(@RequestAttribute Long userId,
                                                  @RequestParam("file") MultipartFile file) {
        return Result.ok(chatService.uploadFile(userId, file));
    }

    /** 查询当前用户已上传的学习资料列表 */
    @GetMapping("/chat/uploaded-files")
    public Result<List<Map<String, Object>>> uploadedFiles(@RequestAttribute Long userId) {
        return Result.ok(chatService.listUploadedFiles(userId));
    }

    // ========== 会话参考资料（选择资料库文件挂到会话 / 临时上传挂到会话） ==========

    /** 当前会话已启用的参考资料列表 */
    @GetMapping("/chat/sessions/{sessionId}/materials")
    public Result<List<Map<String, Object>>> sessionMaterials(@RequestAttribute Long userId,
                                                              @PathVariable Long sessionId) {
        var session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return Result.fail(403, "会话不存在");
        }
        return Result.ok(chatService.listSessionMaterials(userId, sessionId));
    }

    /** 把资料库文件挂到当前会话（重复挂载自动忽略） */
    @PostMapping("/chat/sessions/{sessionId}/materials")
    public Result<Void> addSessionMaterial(@RequestAttribute Long userId,
                                           @PathVariable Long sessionId,
                                           @RequestBody Map<String, Object> body) {
        var session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return Result.fail(403, "会话不存在");
        }
        Long materialId = Long.valueOf(body.get("materialId").toString());
        chatService.addSessionMaterial(userId, sessionId, materialId);
        return Result.ok();
    }

    /** 从当前会话移除参考资料 */
    @DeleteMapping("/chat/sessions/{sessionId}/materials/{materialId}")
    public Result<Void> removeSessionMaterial(@RequestAttribute Long userId,
                                              @PathVariable Long sessionId,
                                              @PathVariable Long materialId) {
        var session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return Result.fail(403, "会话不存在");
        }
        chatService.removeSessionMaterial(userId, sessionId, materialId);
        return Result.ok();
    }

    /** 删除一条已上传的学习资料 */
    @DeleteMapping("/chat/uploaded-files/{recordId}")
    public Result<Void> deleteUploadedFile(@RequestAttribute Long userId,
                                           @PathVariable Long recordId) {
        chatService.deleteUploadedFile(userId, recordId);
        return Result.ok();
    }

    /**
     * SSE 流式聊天（纯文本，无工具）：用 StreamingResponseBody 直接写响应流，每 token 强制 flush
     */
    @PostMapping(path = "/chat/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody chatStream(@RequestAttribute Long userId,
                                            @RequestBody Map<String, Object> body) {
        return streamReply(userId, body, false, false);
    }

    /**
     * SSE 流式聊天（带工具调用）：工具多轮在服务端内部执行，最终回答阶段流式输出。
     * 相比阻塞端点，用户无需等整段生成完，首 token 即可见，感知响应更快。
     */
    @PostMapping(path = "/chat/tool-stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody chatToolStream(@RequestAttribute Long userId,
                                                @RequestBody Map<String, Object> body) {
        return streamReply(userId, body, true, false);
    }

    /**
     * SSE 流式重新生成（带工具）：基于最后一条用户消息重新回答，不重复落库用户消息。
     */
    @PostMapping(path = "/chat/regenerate/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody chatRegenerateStream(@RequestAttribute Long userId,
                                                      @RequestBody Map<String, Object> body) {
        return streamReply(userId, body, true, true);
    }

    /**
     * 流式端点公共实现：校验会话归属、保存用户消息，然后订阅 LLM 流并实时写出。
     * withTools=true 时使用带工具调用的 chatClient；
     * regenerateMode=true 时用户消息不重复落库，消息内容由服务端取最后一条 user 消息。
     */
    private StreamingResponseBody streamReply(Long userId, Map<String, Object> body, boolean withTools, boolean regenerateMode) {
        Long sessionId = Long.valueOf(body.get("sessionId").toString());
        String message = regenerateMode ? null : body.get("message").toString();
        boolean webSearch = body.get("webSearch") instanceof Boolean b && b;

        var session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return out -> {
                out.write("data: [ERROR] 会话不存在\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            };
        }

        // 保存用户消息（重新生成模式不重复落库）
        if (!regenerateMode) {
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(sessionId);
            userMsg.setRole("user");
            userMsg.setContent(message);
            messageMapper.insert(userMsg);
        }

        if ("新对话".equals(session.getTitle()) && !Boolean.TRUE.equals(session.getTitleLocked())) {
            chatService.generateTitleAsync(sessionId, message != null ? message : "");
        }
        sessionMapper.touch(sessionId);

        Flux<String> flux;
        if (regenerateMode) {
            flux = chatService.regenerateStream(sessionId, webSearch);
        } else if (withTools) {
            flux = chatService.chatStreamWithTools(sessionId, message, webSearch);
        } else {
            flux = chatService.chatStream(sessionId, message, webSearch);
        }

        return outputStream -> {
            StringBuilder fullReply = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            String[] errorMsg = {null};
            // 终结标记：保证超时/异常/正常完成只执行一次收尾，避免重复写入占位回复
            AtomicBoolean finished = new AtomicBoolean(false);
            Disposable[] subscription = {null};

            subscription[0] = flux
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            token -> {
                                fullReply.append(token);
                                try {
                                    outputStream.write(token.getBytes(StandardCharsets.UTF_8));
                                    outputStream.flush();
                                } catch (Exception e) {
                                    errorMsg[0] = e.getMessage();
                                    latch.countDown();
                                }
                            },
                            error -> {
                                errorMsg[0] = error.getMessage();
                                if (finished.compareAndSet(false, true)) {
                                    // LLM 失败：写入失败占位回复，保持历史一致
                                    writeFailurePlaceholder(sessionId, message, regenerateMode);
                                    try {
                                        outputStream.write("\n[ERROR] 服务繁忙，请稍后再试".getBytes(StandardCharsets.UTF_8));
                                        outputStream.flush();
                                    } catch (Exception e) {
                                        log.warn("写入错误标记失败: {}", e.getMessage());
                                    }
                                }
                                latch.countDown();
                            },
                            () -> {
                                if (finished.compareAndSet(false, true)) {
                                    try {
                                        // 流结束：保存回复 + 记忆提取 + 更新缓存
                                        String fullText = fullReply.toString();
                                        String cleanReply = regenerateMode
                                                ? chatService.finishRegenerate(sessionId, userId, fullText)
                                                : chatService.finishStream(sessionId, userId, message, fullText);

                                        ChatMessage aiMsg = new ChatMessage();
                                        aiMsg.setSessionId(sessionId);
                                        aiMsg.setRole("assistant");
                                        aiMsg.setContent(cleanReply);
                                        messageMapper.insert(aiMsg);

                                        // 不再自动导入日历事件：AI 会先征求用户"确认导入/调整后再导入"，
                                        // 用户确认后由 AI 调用工具或点击"导入到日历"按钮触发导入

                                        outputStream.write("\n[DONE]".getBytes(StandardCharsets.UTF_8));
                                        outputStream.flush();
                                    } catch (Exception e) {
                                        errorMsg[0] = e.getMessage();
                                    }
                                }
                                latch.countDown();
                            }
                    );

            // 等待流完成；超时则取消订阅（释放 LLM 连接）并写入失败占位
            try {
                if (!latch.await(300, TimeUnit.SECONDS)) {
                    if (finished.compareAndSet(false, true)) {
                        subscription[0].dispose();
                        writeFailurePlaceholder(sessionId, message, regenerateMode);
                        try {
                            outputStream.write("\n[ERROR] 生成超时，请稍后重试".getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                        } catch (Exception e) {
                            log.warn("写入超时错误标记失败: {}", e.getMessage());
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                subscription[0].dispose();
            }
        };
    }

    /**
     * 流式失败时：落库占位回复 + 同步 Redis 历史，保持两份历史一致。
     * regenerateMode 时用户消息已在历史中，只追加占位回复。
     */
    private void writeFailurePlaceholder(Long sessionId, String message, boolean regenerateMode) {
        String placeholder = "抱歉，当前服务繁忙，请稍后再试";
        ChatMessage failMsg = new ChatMessage();
        failMsg.setSessionId(sessionId);
        failMsg.setRole("assistant");
        failMsg.setContent(placeholder);
        try {
            messageMapper.insert(failMsg);
        } catch (Exception e) {
            log.warn("写入失败占位消息失败: {}", e.getMessage());
        }
        if (regenerateMode) {
            chatService.saveRegenerateFailureHistory(sessionId, placeholder);
        } else {
            chatService.saveFailureHistory(sessionId, message, placeholder);
        }
    }
}
