-- [PR] 본인이 고르는 접속 상태 — 온라인(기본) / 자리비움. 근태 상태(근무·외근·출장…)와는 별개.
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'presence_away');
SET @sql = IF(@has_col = 0, 'ALTER TABLE users ADD COLUMN presence_away BOOLEAN NOT NULL DEFAULT FALSE', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
