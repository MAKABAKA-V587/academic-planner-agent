package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学情周报接口
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 获取周报列表（所有历史周报摘要）
     */
    @GetMapping("/weekly/list")
    public Result<List<Map<String, Object>>> getReportList(@RequestAttribute Long userId) {
        return Result.ok(reportService.getReportList(userId));
    }

    /**
     * 根据 ID 获取指定周报内容
     */
    @GetMapping("/weekly/{reportId}")
    public Result<Map<String, Object>> getReportById(@RequestAttribute Long userId,
                                                      @PathVariable Long reportId) {
        String content = reportService.getReportById(userId, reportId);
        if (content == null) {
            return Result.fail(404, "周报不存在");
        }
        return Result.ok(Map.of("content", content));
    }

    /**
     * 获取本周周报（已有则返回，无则返回空）
     */
    @GetMapping("/weekly")
    public Result<Map<String, Object>> getWeeklyReport(@RequestAttribute Long userId) {
        String report = reportService.getWeeklyReport(userId);
        return Result.ok(Map.of("content", report != null ? report : "", "exists", report != null));
    }

    /**
     * 生成本周学习周报（并保存）
     */
    @PostMapping("/weekly")
    public Result<Map<String, String>> generateWeeklyReport(@RequestAttribute Long userId) {
        String report = reportService.generateWeeklyReport(userId);
        return Result.ok(Map.of("content", report));
    }
}
