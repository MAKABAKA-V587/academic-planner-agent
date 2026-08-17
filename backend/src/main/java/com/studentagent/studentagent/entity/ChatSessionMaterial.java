package com.studentagent.studentagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话参考资料关联表 chat_session_material
 */
@Data
public class ChatSessionMaterial {
    private Long id;
    private Long sessionId;
    private Long materialId;
    private LocalDateTime createTime;
}
