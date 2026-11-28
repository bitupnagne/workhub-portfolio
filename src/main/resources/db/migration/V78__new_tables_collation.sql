-- V72~V77 신규 테이블도 V71과 동일한 콜레이션 문제 — leave_types.code = leave_requests.type
-- 조인에서 Illegal mix of collations(1267). 기존 테이블(leave_requests.type)의 실제 콜레이션을
-- 조회해 V71 이후 생성된 테이블 전부를 동적으로 통일한다.
SET @coll = (SELECT COLLATION_NAME FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'leave_requests' AND COLUMN_NAME = 'type');

SET @sql = CONCAT('ALTER TABLE leave_types CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = CONCAT('ALTER TABLE attendance_admin_adjustments CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = CONCAT('ALTER TABLE leave_balance_adjustments CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = CONCAT('ALTER TABLE attendance_month_lock_events CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = CONCAT('ALTER TABLE leave_accrual_policy CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
