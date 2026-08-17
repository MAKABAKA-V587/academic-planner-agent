package com.studentagent.studentagent.service;

import com.studentagent.studentagent.entity.SysUser;
import com.studentagent.studentagent.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final ProfileService profileService;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 用户注册：校验用户名唯一 → BCrypt加密密码 → 写入数据库 → 初始化空档案
     */
    public void register(String username, String password) {
        SysUser existing = userMapper.findByUsername(username);
        if (existing != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        userMapper.insert(user);

        // 注册成功后自动创建空学业档案
        profileService.initProfile(user.getUserId());
    }

    /**
     * 用户登录：校验密码 → 生成UUID Token → 存入Redis(24h) → 返回Token与用户信息
     */
    public LoginResult login(String username, String password) {
        SysUser user = userMapper.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set("token:" + token,
                user.getUserId().toString(), 24, TimeUnit.HOURS);

        return new LoginResult(token, user.getUserId(), user.getUsername());
    }

    /**
     * 退出登录：删除Redis中的Token
     */
    public void logout(String token) {
        redisTemplate.delete("token:" + token);
    }

    /**
     * 更新昵称
     */
    public void updateName(Long userId, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("昵称不能为空");
        }
        if (name.trim().length() > 50) {
            throw new IllegalArgumentException("昵称长度不能超过50");
        }
        userMapper.updateName(userId, name.trim());
    }

    /**
     * 修改密码：校验旧密码 → BCrypt加密新密码
     */
    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = userMapper.findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("原密码错误");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度至少6位");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
    }

    /**
     * 根据用户ID查询用户信息
     */
    public SysUser getById(Long userId) {
        return userMapper.findById(userId);
    }

    /**
     * 校验Token有效性，返回用户ID
     */
    public Long validateToken(String token) {
        String userIdStr = redisTemplate.opsForValue().get("token:" + token);
        if (userIdStr == null) {
            return null;
        }
        return Long.valueOf(userIdStr);
    }

    public record LoginResult(String token, Long userId, String username) {}
}
