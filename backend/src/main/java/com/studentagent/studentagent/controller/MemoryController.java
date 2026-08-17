package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.service.MemoryExtractService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 记忆管理接口（阶段3.6 + 阶段5）
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryExtractService memoryExtractService;

    /**
     * 手动触发记忆提取（异步执行，立即返回）
     */
    @PostMapping("/extract")
    public Result<Map<String, Object>> extract(@RequestAttribute Long userId) {
        memoryExtractService.extractNow(userId);
        return Result.ok(Map.of("accepted", true, "message", "提取已开始，完成后自动更新标签"));
    }

    /**
     * 清除当前用户全部动态提取记忆（保留档案静态记忆）
     */
    @DeleteMapping("/clear")
    public Result<Void> clear(@RequestAttribute Long userId) {
        memoryExtractService.clearMemories(userId);
        return Result.ok();
    }
}
