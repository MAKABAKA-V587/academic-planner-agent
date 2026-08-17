package com.studentagent.studentagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学业档案响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileVO {
    private Long profileId;
    private String weakSubjects;
    private String examPlans;
    private String studyGoals;
}
