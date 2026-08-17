package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.dto.MessageVO;
import com.studentagent.studentagent.dto.SessionVO;
import com.studentagent.studentagent.mapper.SessionMapper;
import com.studentagent.studentagent.service.CalendarService;
import com.studentagent.studentagent.service.ChatService;
import com.studentagent.studentagent.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final SessionMapper sessionMapper;
    private final ChatService chatService;
    private final CalendarService calendarService;

    /**
     * 新建会话
     */
    @PostMapping("/session")
    public Result<Map<String, Object>> createSession(@RequestAttribute Long userId,
                                                     @RequestBody Map<String, String> body) {
        String title = body.get("title");
        Map<String, Object> result = sessionService.createSession(userId, title);
        return Result.ok(result);
    }

    /**
     * 获取当前用户的会话列表
     */
    @GetMapping("/sessions")
    public Result<List<SessionVO>> listSessions(@RequestAttribute Long userId) {
        List<SessionVO> sessions = sessionService.listSessions(userId);
        return Result.ok(sessions);
    }

    /**
     * 删除指定会话（校验归属权，级联删除消息）
     */
    @DeleteMapping("/session/{id}")
    public Result<Void> deleteSession(@RequestAttribute Long userId,
                                       @PathVariable Long id) {
        sessionService.deleteSession(userId, id);
        return Result.ok("删除成功", null);
    }

    /**
     * 重命名会话
     */
    @PutMapping("/session/{id}/title")
    public Result<Void> renameSession(@RequestAttribute Long userId,
                                       @PathVariable Long id,
                                       @RequestBody Map<String, String> body) {
        String title = body.get("title");
        sessionService.renameSession(userId, id, title);
        return Result.ok("重命名成功", null);
    }

    /**
     * 删除本轮对话（最后一条 user + assistant 消息对）
     */
    @DeleteMapping("/session/{id}/last-round")
    public Result<Void> deleteLastRound(@RequestAttribute Long userId,
                                         @PathVariable Long id) {
        sessionService.deleteLastRound(userId, id);
        return Result.ok("删除成功", null);
    }

    /**
     * 重新生成最后一条 AI 回复
     */
    @PostMapping("/chat/regenerate")
    public Result<Map<String, String>> regenerate(@RequestAttribute Long userId,
                                                   @RequestBody Map<String, Object> body) {
        Long sessionId = Long.valueOf(body.get("sessionId").toString());
        boolean webSearch = body.get("webSearch") instanceof Boolean b && b;
        var session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return Result.fail(403, "会话不存在");
        }
        String reply = chatService.regenerateReply(sessionId, webSearch);
        // 自动提取日历事件
        calendarService.autoExtractEvents(userId, "", reply);
        return Result.ok(Map.of("role", "assistant", "content", reply));
    }

    /**
     * 获取指定会话的历史消息，时间正序
     */
    @GetMapping("/session/{id}/messages")
    public Result<List<MessageVO>> getMessages(@RequestAttribute Long userId,
                                                @PathVariable Long id) {
        List<MessageVO> messages = sessionService.getMessages(userId, id);
        return Result.ok(messages);
    }

    /**
     * 编辑单条消息（级联删除其后已过期的回复）
     */
    @PutMapping("/message/{messageId}/edit")
    public Result<Void> editMessage(@RequestAttribute Long userId,
                                     @PathVariable Long messageId,
                                     @RequestBody Map<String, String> body) {
        sessionService.editMessage(userId, messageId, body.get("content"));
        return Result.ok("修改成功", null);
    }

    /**
     * 删除单条消息及之后的所有消息
     */
    @DeleteMapping("/message/{messageId}")
    public Result<Void> deleteMessage(@RequestAttribute Long userId,
                                       @PathVariable Long messageId) {
        sessionService.deleteMessage(userId, messageId);
        return Result.ok("删除成功", null);
    }
}
