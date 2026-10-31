-- Phase 3: additive, repeatable migration. Existing project/user data is retained.
USE campus_market;
CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(160) CHARACTER SET ascii NOT NULL,
    nickname VARCHAR(40) NOT NULL,
    school VARCHAR(80) NOT NULL DEFAULT '',
    campus VARCHAR(80) NOT NULL DEFAULT '',
    bio VARCHAR(200) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until DATETIME(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS auth_sessions (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_sessions_user (user_id),
    INDEX idx_sessions_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE app_metadata SET schema_version = GREATEST(schema_version, 2) WHERE id = 1;
