package com.studentagent.studentagent.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 学业档案更新请求
 */
@Data
public class ProfileDTO {

    @Size(max = 500, message = "薄弱科目不能超过500字符")
    private String weakSubjects;

    @Size(max = 500, message = "考试计划不能超过500字符")
    private String examPlans;

    @Size(max = 500, message = "学习目标不能超过500字符")
    private String studyGoals;
}
