package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.dto.ProfileDTO;
import com.studentagent.studentagent.dto.ProfileVO;
import com.studentagent.studentagent.dto.TagVO;
import com.studentagent.studentagent.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /**
     * 获取当前用户的学业档案
     */
    @GetMapping
    public Result<ProfileVO> getProfile(@RequestAttribute Long userId) {
        ProfileVO profile = profileService.getProfile(userId);
        return Result.ok(profile);
    }

    /**
     * 更新学业档案
     */
    @PutMapping
    public Result<Void> updateProfile(@RequestAttribute Long userId,
                                      @Valid @RequestBody ProfileDTO dto) {
        profileService.updateProfile(userId, dto);
        return Result.ok();
    }

    /**
     * 获取用户学习画像标签（阶段5）
     */
    @GetMapping("/tags")
    public Result<List<TagVO>> getTags(@RequestAttribute Long userId) {
        List<TagVO> tags = profileService.getTags(userId);
        return Result.ok(tags);
    }
}
