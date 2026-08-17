package com.studentagent.studentagent.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WeeklyReport {
    private Long reportId;
    private Long userId;
    private String weekStart;   // 周一日期 yyyy-MM-dd
    private String weekEnd;     // 周日日期 yyyy-MM-dd
    private String content;     // Markdown 周报内容
    private LocalDateTime createTime;
}
