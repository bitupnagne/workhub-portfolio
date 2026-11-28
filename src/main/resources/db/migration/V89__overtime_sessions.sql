-- [OT] 시간외 출퇴근 기록 — 정규 근무(attendance_records, 하루 1건) 밖에서 찍힌 출퇴근.
--
-- 사전 신청이 원칙이지만 급한 일로 신청 없이 나오는 경우가 있다. 그때 찍은 출퇴근이 남아 있어야
-- 나중에 시간외 근무를 <증적과 함께> 올릴 수 있다. 정규 기록은 (직원, 근무일) 유일이라
-- 퇴근 뒤 다시 나온 근무나 휴일 근무를 담을 수 없어 따로 둔다 — 하루에 여러 건이 될 수 있다.
--
-- 여기 들어오는 경우:
--   · 회사 휴무일·휴무 요일에 찍은 출근
--   · 출근 기록 없이 기준 퇴근시각을 지나서 찍은 출근
--   · 그날 퇴근까지 마친 뒤 다시 찍은 출근
-- 지각·조퇴 같은 근태 판정은 하지 않는다. 승인된 시간외 신청과 겹치는 만큼만 인정 시간이 된다.
CREATE TABLE IF NOT EXISTS overtime_sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  work_date DATE NOT NULL,
  check_in_at DATETIME NOT NULL,
  check_out_at DATETIME NULL,
  -- WEB / DESKTOP / DEVICE(외부 출입 단말)
  source VARCHAR(20) NOT NULL DEFAULT 'WEB',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY ix_overtime_sessions_user_date (user_id, work_date),
  CONSTRAINT fk_overtime_sessions_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 미신청 안내 기준 — 기준 근무시간을 이만큼 넘겨 일한 날부터 "신청하지 않은 시간외 근무"로 알린다.
-- 몇 분 늦게 나간 것까지 알리면 안내가 소음이 된다.
SET @has_threshold = (SELECT COUNT(*) FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'overtime_settings'
                        AND COLUMN_NAME = 'notice_threshold_minutes');
SET @sql = IF(@has_threshold = 0,
              'ALTER TABLE overtime_settings ADD COLUMN notice_threshold_minutes INT NOT NULL DEFAULT 30 AFTER daily_standard_hours',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
