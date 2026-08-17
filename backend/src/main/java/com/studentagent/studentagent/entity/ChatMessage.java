package com.studentagent.studentagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表 chat_message
 */
@Data
public class ChatMessage {
    private Long messageId;
    private Long sessionId;
    private String role;
    private String content;
    private LocalDateTime createTime;
}
