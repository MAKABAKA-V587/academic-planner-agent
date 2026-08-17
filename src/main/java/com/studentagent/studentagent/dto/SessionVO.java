package com.studentagent.studentagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionVO {
    private Long sessionId;
    private String title;
    private LocalDateTime lastActiveTime;
}
