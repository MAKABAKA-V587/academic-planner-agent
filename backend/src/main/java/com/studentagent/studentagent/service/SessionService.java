package com.studentagent.studentagent.service;

import com.studentagent.studentagent.dto.MessageVO;
import com.studentagent.studentagent.dto.SessionVO;
import com.studentagent.studentagent.entity.ChatMessage;
import com.studentagent.studentagent.entity.ChatSession;
import com.studentagent.studentagent.mapper.MessageMapper;
import com.studentagent.studentagent.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final ChatService chatService;

    /**
     * 新建会话
     */
    public Map<String, Object> createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title != null && !title.isBlank() ? title : "新对话");
        sessionMapper.insert(session);
        return Map.of("sessionId", session.getSessionId(), "title", session.getTitle());
    }

    /**
     * 会话列表，按最后活跃时间倒序
     */
    public List<SessionVO> listSessions(Long userId) {
        List<ChatSession> sessions = sessionMapper.findByUserId(userId);
        return sessions.stream()
                .map(s -> new SessionVO(s.getSessionId(), s.getTitle(), s.getLastActiveTime()))
                .collect(Collectors.toList());
    }

    /**
     * 删除会话，校验归属权后级联删除消息
     */
    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        ChatSession session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("会话不存在或无权限");
        }
        messageMapper.deleteBySessionId(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    /**
     * 重命名会话
     */
    public void renameSession(Long userId, Long sessionId, String title) {
        ChatSession session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("会话不存在或无权限");
        }
        if (title == null || title.isBlank()) return;
        sessionMapper.renameAndLock(sessionId, title);
    }

    /**
     * 删除最后一整轮对话（最后一条 user + 后续 tool_call/tool_result + assistant 消息）
     */
    @Transactional
    public void deleteLastRound(Long userId, Long sessionId) {
        ChatSession session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("会话不存在或无权限");
        }
        Long lastUserMessageId = messageMapper.getLastUserMessageId(sessionId);
        if (lastUserMessageId != null) {
            messageMapper.deleteFrom(sessionId, lastUserMessageId);
        }
        chatService.rebuildHistory(sessionId);
    }

    /**
     * 获取指定会话的历史消息，时间正序
     */
    public List<MessageVO> getMessages(Long userId, Long sessionId) {
        ChatSession session = sessionMapper.findById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("会话不存在或无权限");
        }
        List<ChatMessage> messages = messageMapper.findBySessionId(sessionId);
        return messages.stream()
                .map(m -> new MessageVO(m.getMessageId(), m.getRole(), m.getContent(), m.getCreateTime()))
                .collect(Collectors.toList());
    }

    /**
     * 编辑单条消息：更新内容后级联删除其后的回复（基于旧文本，已过期），并同步 Redis 历史
     */
    @Transactional
    public void editMessage(Long userId, Long messageId, String newContent) {
        ChatMessage message = requireOwnedMessage(userId, messageId);
        if (newContent == null || newContent.isBlank()) {
            throw new RuntimeException("消息内容不能为空");
        }
        messageMapper.updateContent(messageId, newContent);
        messageMapper.deleteAfter(message.getSessionId(), messageId);
        chatService.rebuildHistory(message.getSessionId());
    }

    /**
     * 删除单条消息及之后的所有消息（截断会话到此为止），并同步 Redis 历史
     */
    @Transactional
    public void deleteMessage(Long userId, Long messageId) {
        ChatMessage message = requireOwnedMessage(userId, messageId);
        messageMapper.deleteFrom(message.getSessionId(), messageId);
        chatService.rebuildHistory(message.getSessionId());
    }

    /** 校验消息归属权，返回消息本身 */
    private ChatMessage requireOwnedMessage(Long userId, Long messageId) {
        ChatMessage message = messageMapper.findById(messageId);
        if (message == null) {
            throw new RuntimeException("消息不存在");
        }
        ChatSession session = sessionMapper.findById(message.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new RuntimeException("消息不存在或无权限");
        }
        return message;
    }
}
