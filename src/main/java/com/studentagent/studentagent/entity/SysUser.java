package com.studentagent.studentagent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表 sys_user
 */
@Data
public class SysUser {
    private Long userId;
    private String username;
    private String password;
    private String name;
    private String major;
    private String grade;
    private String userTags;
    private LocalDateTime createTime;
}
