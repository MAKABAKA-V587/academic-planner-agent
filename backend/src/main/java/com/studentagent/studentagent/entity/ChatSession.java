package com.studentagent.studentagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话表 chat_session
 */
@Data
public class ChatSession {
    private Long sessionId;
    private Long userId;
    private String title;
    private Boolean titleLocked;
    private String summary;
    private Long summaryUpTo;
    private LocalDateTime createTime;
    private LocalDateTime lastActiveTime;
}
