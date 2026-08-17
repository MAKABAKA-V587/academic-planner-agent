package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.service.MaterialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 资料库接口：上传/查看/下载学习资料原件
 */
@Slf4j
@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;

    /** 上传资料。temp=true 为临时上传（只挂当前会话，不进资料库）；默认进入资料库永久保存 */
    @PostMapping
    public Result<Map<String, Object>> upload(@RequestAttribute Long userId,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "temp", defaultValue = "false") boolean temp) {
        return Result.ok(materialService.upload(userId, file, temp));
    }

    /** 资料库列表（最新在前） */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestAttribute Long userId) {
        return Result.ok(materialService.list(userId));
    }

    /** 查看资料详情（原文），前端预览/下载用 */
    @GetMapping("/{materialId}")
    public Result<Map<String, Object>> detail(@RequestAttribute Long userId,
                                              @PathVariable Long materialId) {
        return Result.ok(materialService.detail(userId, materialId));
    }

    /** 删除资料（连同 AI 参考副本） */
    @DeleteMapping("/{materialId}")
    public Result<Void> delete(@RequestAttribute Long userId,
                               @PathVariable Long materialId) {
        materialService.delete(userId, materialId);
        return Result.ok();
    }
}
