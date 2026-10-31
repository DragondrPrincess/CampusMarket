-- Phase 2 infrastructure only. Never drop databases, tables, or existing rows.
CREATE DATABASE IF NOT EXISTS campus_market CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE campus_market;
CREATE TABLE IF NOT EXISTS app_metadata (
    id BIGINT NOT NULL PRIMARY KEY,
    project_name VARCHAR(100) NOT NULL,
    schema_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO app_metadata (id, project_name, schema_version)
SELECT 1, '校园易物', 1
WHERE NOT EXISTS (SELECT 1 FROM app_metadata WHERE id = 1);
