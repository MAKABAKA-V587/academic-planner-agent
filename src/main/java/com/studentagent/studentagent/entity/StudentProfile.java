package com.studentagent.studentagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学业档案表 student_profile
 */
@Data
public class StudentProfile {
    private Long profileId;
    private Long userId;
    private String weakSubjects;
    private String examPlans;
    private String studyGoals;
    private LocalDateTime updateTime;
}
