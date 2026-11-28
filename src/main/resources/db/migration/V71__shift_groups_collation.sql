-- V69/V70 신규 테이블이 서버 기본 콜레이션으로 생성되어, 기존 테이블과 콜레이션이 다르면
-- shift_groups.code = flex_time_requests.shift_group 조인에서 'Illegal mix of collations'(1267) 발생.
-- 환경마다 기본 콜레이션이 달라(로컬 MariaDB11=uca1400, 운영 10.11 별도) 하드코딩 대신
-- 기존 테이블(flex_time_requests.shift_group)의 실제 콜레이션을 조회해 동적으로 통일한다.
SET @coll = (SELECT COLLATION_NAME FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'flex_time_requests' AND COLUMN_NAME = 'shift_group');

SET @sql = CONCAT('ALTER TABLE shift_groups CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = CONCAT('ALTER TABLE attendance_week_schedule CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
