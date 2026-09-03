-- Testcontainers 集成测试专用最小建表脚本（仅覆盖测试涉及的表）
CREATE TABLE IF NOT EXISTS sys_user (
    user_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50) NOT NULL UNIQUE,
    password    VARCHAR(100) NOT NULL,
    name        VARCHAR(50),
    major       VARCHAR(100),
    grade       VARCHAR(20),
    user_tags   VARCHAR(500),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_session (
    session_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    title            VARCHAR(128),
    title_locked     TINYINT DEFAULT 0,
    summary          TEXT NULL,
    summary_up_to    BIGINT NULL,
    create_time      DATETIME DEFAULT CURRENT_TIMESTAMP,
    last_active_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
    message_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  BIGINT NOT NULL,
    role        VARCHAR(20) NOT NULL,
    content     TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS memory_record (
    record_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    memory_text VARCHAR(512) NOT NULL,
    vector_id   VARCHAR(64) NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id),
    INDEX idx_null_vector (vector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
