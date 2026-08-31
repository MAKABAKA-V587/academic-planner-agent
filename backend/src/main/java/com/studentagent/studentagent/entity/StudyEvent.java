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
    /** 跨天任务按天打卡的完成日期（逗号分隔，如 "2026-08-31,2026-09-01"），单日任务为 null */
    private String completedDates;
    private LocalDateTime createTime;
}
