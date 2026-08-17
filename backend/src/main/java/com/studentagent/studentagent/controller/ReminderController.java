package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 智能复习提醒接口
 */
@RestController
@RequestMapping("/api/reminder")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

    /**
     * 获取当前用户的提醒列表（登录时自动调用）
     */
    @GetMapping("/check")
    public Result<List<String>> checkReminders(@RequestAttribute Long userId) {
        List<String> reminders = reminderService.checkReminders(userId);
        return Result.ok(reminders);
    }
}
