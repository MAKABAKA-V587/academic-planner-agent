package com.studentagent.studentagent.controller;

import com.studentagent.studentagent.common.Result;
import com.studentagent.studentagent.dto.LoginDTO;
import com.studentagent.studentagent.dto.RegisterDTO;
import com.studentagent.studentagent.entity.SysUser;
import com.studentagent.studentagent.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        userService.register(dto.getUsername(), dto.getPassword());
        return Result.ok("注册成功", null);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<UserService.LoginResult> login(@Valid @RequestBody LoginDTO dto) {
        UserService.LoginResult result = userService.login(dto.getUsername(), dto.getPassword());
        return Result.ok("登录成功", result);
    }

    /**
     * 当前登录用户信息
     */
    @GetMapping("/info")
    public Result<Map<String, Object>> info(@RequestAttribute Long userId) {
        SysUser user = userService.getById(userId);
        if (user == null) {
            return Result.fail(404, "用户不存在");
        }
        return Result.ok(Map.of(
                "userId", user.getUserId(),
                "username", user.getUsername(),
                "name", user.getName() != null ? user.getName() : ""
        ));
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        userService.logout(token);
        return Result.ok("退出成功", null);
    }

    /**
     * 更新昵称
     */
    @PutMapping("/info")
    public Result<Void> updateInfo(@RequestAttribute Long userId, @RequestBody Map<String, String> body) {
        userService.updateName(userId, body.get("name"));
        return Result.ok("昵称更新成功", null);
    }

    /**
     * 修改密码
     */
    @PutMapping("/password")
    public Result<Void> updatePassword(@RequestAttribute Long userId, @RequestBody Map<String, String> body) {
        userService.updatePassword(userId, body.get("oldPassword"), body.get("newPassword"));
        return Result.ok("密码修改成功", null);
    }
}
