package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.entity.StudyEvent;
import com.studentagent.studentagent.service.CalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 学习日历接口
 */
@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;

    /**
     * 获取指定月份的事件列表
     */
    @GetMapping
    public Result<List<StudyEvent>> getMonthEvents(@RequestAttribute Long userId,
                                                    @RequestParam(defaultValue = "") String month) {
        if (month.isBlank()) {
            month = java.time.YearMonth.now().toString(); // "yyyy-MM"
        }
        List<StudyEvent> events = calendarService.getMonthEvents(userId, month);
        return Result.ok(events);
    }

    /**
     * 手动添加事件
     */
    @PostMapping
    public Result<StudyEvent> addEvent(@RequestAttribute Long userId,
                                        @RequestBody StudyEvent event) {
        StudyEvent created = calendarService.addEvent(userId, event);
        return Result.ok(created);
    }

    /**
     * 更新事件
     */
    @PutMapping("/{id}")
    public Result<Void> updateEvent(@RequestAttribute Long userId,
                                     @PathVariable Long id,
                                     @RequestBody StudyEvent event) {
        calendarService.updateEvent(userId, id, event);
        return Result.ok();
    }

    /**
     * 删除事件
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteEvent(@RequestAttribute Long userId,
                                     @PathVariable Long id) {
        calendarService.deleteEvent(userId, id);
        return Result.ok();
    }

    /** 手动导入：从文本中提取事件 */
    @PostMapping("/extract-text")
    public Result<Map<String, Object>> extractFromText(@RequestAttribute Long userId,
                                                        @RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) return Result.fail("文本为空");
        int count = calendarService.extractAndSave(userId, text);
        return Result.ok(Map.of("count", count));
    }

    /** 清空当前用户所有日历事件 */
    @DeleteMapping("/all")
    public Result<Map<String, Object>> clearAll(@RequestAttribute Long userId) {
        int count = calendarService.clearAll(userId);
        return Result.ok(Map.of("count", count));
    }

    /** 获取今日任务列表 */
    @GetMapping("/today")
    public Result<List<StudyEvent>> getTodayTasks(@RequestAttribute Long userId) {
        List<StudyEvent> tasks = calendarService.getTodayTasks(userId);
        return Result.ok(tasks);
    }

    /** 切换任务完成状态 */
    @PutMapping("/{id}/complete")
    public Result<Void> toggleComplete(@RequestAttribute Long userId,
                                        @PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        Boolean completed = (Boolean) body.get("completed");
        calendarService.toggleComplete(userId, id, completed);
        return Result.ok();
    }
}
