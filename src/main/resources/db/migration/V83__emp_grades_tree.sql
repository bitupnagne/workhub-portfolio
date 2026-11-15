-- [D5] 직급 체계를 무한 트리로 — '급' 아래 '호봉', 그 아래로도 계속 만들 수 있게 한다.
--
-- 왜 트리인가: 지금은 '1급~7급' 평면 목록이라 호봉을 넣으려면 '3급 5호봉'을 이름 하나로
-- 만들어야 하고, 그러면 급수별 집계·정렬이 이름 문자열 파싱이 된다. 부모를 두면 급은 급대로
-- 남고 호봉은 그 아래에 붙는다.
--
-- ★ 인사평가와의 관계 — eval_group 을 NULL 허용으로 바꾼다.
--   인사평가는 등급 <이름>을 보지 않고 eval_group 코드(CEO/EXEC/G12/G34/G57)만 본다.
--   호봉마다 이 코드를 다시 지정하게 하면 실수로 급과 달라질 수 있으므로, 비워 두면
--   <가장 가까운 조상의 값을 상속>한다. '3급'에 G34 를 달아 두면 그 아래 1~10호봉이 전부
--   G34 다. 특정 호봉만 달라야 하면 그 노드에 직접 지정한다.
--   해석은 EmpGradeService.gradeGroupById() 한 곳에서 하므로 평가 로직은 그대로다.
--
-- ★ 이름 유일성 — 전역 UNIQUE 를 없앤다.
--   '1호봉'은 급마다 있어야 한다. 형제 사이에서만 유일하면 되는데, MariaDB 의 UNIQUE 는
--   NULL 을 서로 다른 값으로 보므로 최상위(parent_id IS NULL) 형제에는 제약이 걸리지 않는다.
--   그래서 DB 제약 대신 앱에서 형제 단위로 검사한다(EmpGradeService.requireName).
--   여기서는 조회용 인덱스만 남긴다.

SET @has_parent = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emp_grades' AND COLUMN_NAME = 'parent_id');
SET @sql = IF(@has_parent = 0,
              'ALTER TABLE emp_grades ADD COLUMN parent_id BIGINT NULL AFTER id',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_fk = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emp_grades'
                 AND CONSTRAINT_NAME = 'fk_emp_grades_parent');
SET @sql = IF(@has_fk = 0,
              'ALTER TABLE emp_grades ADD CONSTRAINT fk_emp_grades_parent FOREIGN KEY (parent_id) REFERENCES emp_grades(id)',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 자식은 조상에서 상속받으므로 비워 둘 수 있어야 한다.
ALTER TABLE emp_grades MODIFY COLUMN eval_group VARCHAR(10) NULL;

-- 전역 이름 UNIQUE 제거(형제 단위 검사는 앱에서).
SET @has_uk = (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emp_grades'
                 AND INDEX_NAME = 'uk_emp_grades_name');
SET @sql = IF(@has_uk > 0, 'ALTER TABLE emp_grades DROP INDEX uk_emp_grades_name', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emp_grades'
                  AND INDEX_NAME = 'ix_emp_grades_parent');
SET @sql = IF(@has_idx = 0,
              'CREATE INDEX ix_emp_grades_parent ON emp_grades(parent_id, display_order)',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 기존 9개 등급은 전부 최상위로 남는다(parent_id NULL) — 이번 이관으로 바뀌는 값이 없다.
