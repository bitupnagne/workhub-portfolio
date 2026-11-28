-- [GRADE] 직급 = 값 + 단위. 둘 다 자유 텍스트다 — '3' + '급', '선임' + '', 'A' + '직급'.
--
-- 단위는 단계(깊이)마다 한 번 정해 두고(grade_depth_units: 0=최상위 '급', 1='호봉'), 특정 노드에서만 바꿀 수 있다
-- (emp_grades.unit_label: NULL=단계 기본을 따름, ''=단위 없음). 보이는 이름(emp_grades.name)은 값+단위를 붙여
-- 저장해 둔다 — 직원 목록·결재 문서·인사카드 등 기존 화면이 name 을 그대로 읽기 때문이다.
-- 순위는 값의 크기가 아니라 형제 순서(display_order)와 트리 위치로 정한다(값이 글자일 수 있으므로).
SET @has_value = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'emp_grades' AND COLUMN_NAME = 'grade_value');
SET @sql = IF(@has_value = 0,
              'ALTER TABLE emp_grades ADD COLUMN grade_value VARCHAR(50) NULL AFTER name, ADD COLUMN unit_label VARCHAR(20) NULL AFTER grade_value',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 기존 이름은 통째로 값으로 옮기고 단위는 '없음'('')으로 못 박는다. 단계 단위를 나중에 정해도 '1급'이
-- '1급급', '대표이사'가 '대표이사급'이 되지 않게 하기 위해서다. '1급'을 값 '1' + 단계 단위로 바꾸려면
-- 그 직급을 열어 값과 단위를 고치면 된다.
UPDATE emp_grades SET grade_value = name, unit_label = '' WHERE grade_value IS NULL;

CREATE TABLE IF NOT EXISTS grade_depth_units (
  depth INT NOT NULL PRIMARY KEY,
  unit_label VARCHAR(20) NOT NULL,
  updated_by BIGINT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- [PAY] 직급별 통상시급 — 적용 시작일별 이력.
--
-- 연봉 인상처럼 값이 바뀌어도 지난달 시간외 수당·작년 연차수당이 소급해서 바뀌면 안 된다. 그래서 덮어쓰지 않고
-- '이날부터 이 금액'으로 쌓고, 계산할 때 그 날짜에 유효한 값을 고른다. 노드에 값이 없으면 가장 가까운 상위
-- 직급 값을 쓴다('3급'에만 넣으면 그 아래 호봉 전부). 행이 없으면 '미설정'이다 — 0원과 구분한다.
--
-- 연차수당 1일 단가(V82 leave_allowance_rates)는 더 쓰지 않는다: 연차수당 = 통상시급 × 기준 시간 × 잔여일.
-- 옮길 단가 데이터는 없다(테이블은 기록으로 남겨 둔다).
CREATE TABLE IF NOT EXISTS grade_hourly_wages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  grade_id BIGINT NOT NULL,
  effective_from DATE NOT NULL,
  hourly_wage DECIMAL(12,0) NOT NULL,
  note VARCHAR(200) NULL,
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_grade_hourly_wage (grade_id, effective_from),
  CONSTRAINT fk_grade_hourly_wage_grade FOREIGN KEY (grade_id) REFERENCES emp_grades(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 바뀐 내역(누가 언제 어떤 직급 시급을 넣고 지웠는지).
CREATE TABLE IF NOT EXISTS grade_hourly_wage_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  grade_id BIGINT NULL,
  grade_name VARCHAR(200) NOT NULL,
  action VARCHAR(10) NOT NULL,
  effective_from DATE NOT NULL,
  hourly_wage DECIMAL(12,0) NULL,
  actor_id BIGINT NULL,
  actor_name VARCHAR(50) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_grade_hourly_wage_logs_grade (grade_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
