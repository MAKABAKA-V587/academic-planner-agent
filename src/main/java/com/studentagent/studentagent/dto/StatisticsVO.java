package com.studentagent.studentagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 学习数据看板 — 4组图表数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsVO {

    /** 科目分布饼图：[{name:"数据结构", value:5}, ...] */
    private List<Map<String, Object>> subjectDistribution;

    /** 薄弱项雷达图：[{name:"线性代数", value:8}, ...] */
    private List<Map<String, Object>> weaknessRadar;

    /** 学习活跃趋势（近7天）：[{date:"2026-07-22", count:3}, ...] */
    private List<Map<String, Object>> activityTrend;

    /** 记忆增长曲线（近7天）：[{date:"2026-07-22", count:2}, ...] */
    private List<Map<String, Object>> memoryGrowth;

    /** 学习进度总览：{total, completed, pending, completionRate, thisWeekCompleted, streak} */
    private Map<String, Object> progressOverview;

    /** 每日完成趋势（近14天）：[{date:"2026-07-22", count:1}, ...] */
    private List<Map<String, Object>> completionTrend;
}
