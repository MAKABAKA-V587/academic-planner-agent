package com.studentagent.studentagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {
    private Long messageId;
    private String role;
    private String content;
    private LocalDateTime createTime;
}
