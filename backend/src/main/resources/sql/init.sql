-- =============================================
-- 学业规划智能Agent系统 - 数据库初始化脚本
-- 数据库: student_agent
-- 字符集: utf8mb4
-- =============================================

CREATE DATABASE IF NOT EXISTS student_agent
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE student_agent;

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    user_id     BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username    VARCHAR(50)  NOT NULL UNIQUE                COMMENT '登录用户名',
    password    VARCHAR(100) NOT NULL                       COMMENT 'BCrypt加密密码',
    name        VARCHAR(50)  DEFAULT NULL                   COMMENT '真实姓名',
    major       VARCHAR(100) DEFAULT NULL                   COMMENT '专业',
    grade       VARCHAR(50)  DEFAULT NULL                   COMMENT '年级',
    user_tags   VARCHAR(500) DEFAULT NULL                   COMMENT '学习画像标签',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB COMMENT='用户表';

-- 学业档案表
CREATE TABLE IF NOT EXISTS student_profile (
    profile_id    BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '档案ID',
    user_id       BIGINT   NOT NULL UNIQUE                COMMENT '关联用户ID',
    weak_subjects TEXT     DEFAULT NULL                   COMMENT '薄弱科目',
    exam_plans    TEXT     DEFAULT NULL                   COMMENT '考试计划',
    study_goals   TEXT     DEFAULT NULL                   COMMENT '学习目标',
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB COMMENT='学业档案表';

-- 会话表
CREATE TABLE IF NOT EXISTS chat_session (
    session_id       BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '会话ID',
    user_id          BIGINT       NOT NULL                      COMMENT '所属用户ID',
    title            VARCHAR(200) NOT NULL                      COMMENT '会话标题',
    title_locked     TINYINT(1)   NOT NULL DEFAULT 0            COMMENT '标题是否锁定(0=未锁定 1=已锁定)',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    last_active_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='会话表';

-- 消息表
CREATE TABLE IF NOT EXISTS chat_message (
    message_id  BIGINT      PRIMARY KEY AUTO_INCREMENT COMMENT '消息ID',
    session_id  BIGINT      NOT NULL                      COMMENT '所属会话ID',
    role        VARCHAR(20) NOT NULL                      COMMENT '消息角色: user/assistant/tool',
    content     TEXT        NOT NULL                      COMMENT '消息内容',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息发送时间',
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB COMMENT='消息表';

-- 长时记忆表
CREATE TABLE IF NOT EXISTS memory_record (
    record_id   BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    user_id     BIGINT       NOT NULL                      COMMENT '所属用户ID',
    memory_text TEXT         NOT NULL                      COMMENT '记忆文本内容',
    vector_id   VARCHAR(100) NOT NULL UNIQUE               COMMENT 'Chroma文档ID',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记忆创建时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='长时记忆表';

-- 学习日历事件表
CREATE TABLE IF NOT EXISTS study_event (
    event_id    BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '事件ID',
    user_id     BIGINT       NOT NULL                      COMMENT '所属用户ID',
    title       VARCHAR(200) NOT NULL                      COMMENT '事件标题',
    event_date  DATE         NOT NULL                      COMMENT '事件日期',
    end_date    DATE         DEFAULT NULL                  COMMENT '结束日期（阶段任务用）',
    description TEXT         DEFAULT NULL                  COMMENT '详细描述',
    event_type  VARCHAR(20)  NOT NULL DEFAULT 'task'       COMMENT '事件类型: plan/exam/task',
    source      VARCHAR(20)  NOT NULL DEFAULT 'manual'     COMMENT '来源: ai/manual',
    color       VARCHAR(20)  DEFAULT '#409EFF'             COMMENT '事件颜色标记',
    completed   TINYINT(1)   NOT NULL DEFAULT 0            COMMENT '是否完成(0=未完成 1=已完成，仅单日任务用)',
    completed_dates TEXT       DEFAULT NULL                COMMENT '跨天任务按天打卡的完成日期，逗号分隔如 2026-08-31,2026-09-01',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_event_date (event_date)
) ENGINE=InnoDB COMMENT='学习日历事件表';

-- 学情周报表
CREATE TABLE IF NOT EXISTS weekly_report (
    report_id   BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '报告ID',
    user_id     BIGINT       NOT NULL                      COMMENT '所属用户ID',
    week_start  DATE         NOT NULL                      COMMENT '周起始日期（周一）',
    week_end    DATE         NOT NULL                      COMMENT '周结束日期（周日）',
    content     MEDIUMTEXT   NOT NULL                      COMMENT 'Markdown 周报内容',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_week (user_id, week_start)
) ENGINE=InnoDB COMMENT='学情周报表';

-- 资料库表（用户上传的学习资料原件，独立于记忆表，不受记忆清理影响）
CREATE TABLE IF NOT EXISTS study_material (
    material_id      BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '资料ID',
    user_id          BIGINT       NOT NULL                      COMMENT '所属用户ID',
    file_name        VARCHAR(200) NOT NULL                      COMMENT '文件名',
    file_type        VARCHAR(20)  DEFAULT 'txt'                 COMMENT '文件类型: txt/md/csv',
    file_size        BIGINT       DEFAULT 0                     COMMENT '文件大小(字节)',
    content_text     MEDIUMTEXT   NOT NULL                      COMMENT '资料原文',
    memory_record_id BIGINT       DEFAULT NULL                  COMMENT '关联的记忆记录ID(AI参考副本)',
    is_temp          TINYINT      DEFAULT 0                     COMMENT '1=临时上传(只挂当前会话) 0=资料库永久文件',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='资料库表';

-- 会话参考资料关联表（用户选择/临时上传的资料挂到会话，AI 对话时参考）
CREATE TABLE IF NOT EXISTS chat_session_material (
    id          BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '关联ID',
    session_id  BIGINT   NOT NULL                      COMMENT '会话ID',
    material_id BIGINT   NOT NULL                      COMMENT '资料ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
    UNIQUE KEY uk_session_material (session_id, material_id),
    INDEX idx_session_id (session_id)
) ENGINE=InnoDB COMMENT='会话参考资料关联表';
