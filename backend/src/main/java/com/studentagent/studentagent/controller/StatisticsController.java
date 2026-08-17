package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.dto.StatisticsVO;
import com.studentagent.studentagent.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 学习数据看板接口
 */
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    /**
     * 获取学习数据看板全部图表数据
     */
    @GetMapping("/overview")
    public Result<StatisticsVO> getOverview(@RequestAttribute Long userId) {
        StatisticsVO data = statisticsService.getOverview(userId);
        return Result.ok(data);
    }
}
