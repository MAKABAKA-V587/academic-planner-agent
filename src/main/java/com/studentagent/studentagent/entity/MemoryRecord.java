package com.studentagent.studentagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 长时记忆表 memory_record
 */
@Data
public class MemoryRecord {
    private Long recordId;
    private Long userId;
    private String memoryText;
    private String vectorId;
    private LocalDateTime createTime;
}
