package com.studentagent.studentagent.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习日历事件表 study_event
 */
@Data
public class StudyEvent {
    private Long eventId;
    private Long userId;
    private String title;
    private LocalDate eventDate;
    private LocalDate endDate;
    private String description;
    private String eventType;
    private String source;
    private String color;
    private Boolean completed;
    private LocalDateTime createTime;
}
