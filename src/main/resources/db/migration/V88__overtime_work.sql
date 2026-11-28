-- [OT] 시간외 근무 — 설정(가산율·야간 시간대·조별 기준)과 신청·결재.
--
-- 지금까지는 출근·퇴근 시각만 있었고 "기준 시간을 넘겨 얼마나 더 일했는가"를 남길 곳이 없었다.
-- 시간외 근무는 <신청·승인된 것만> 인정하는 것이 원칙이라 다른 신청서와 같은 모양의 문서로 둔다.
-- 다만 급한 일로 신청 없이 나온 경우가 있으므로 사후 신청(POST)을 허용한다 — 근무한 달 안에서만.

-- ── 전사 설정(1행) ───────────────────────────────────────────
-- 가산율은 전부 "통상시급 × N"의 N 이다. 연장 1.5 는 <기본 1 + 가산 0.5>를 합친 값이고,
-- 야간은 다른 구분과 겹쳐 붙는 <추가분>이라 0.5 다(연장+야간이면 1.5 + 0.5 = 2.0).
-- 통상시급은 따로 받지 않는다 — 연차수당 1일 단가(leave_allowance_rates) ÷ daily_standard_hours 로 역산한다.
CREATE TABLE IF NOT EXISTS overtime_settings (
  id TINYINT PRIMARY KEY,
  night_start TIME NOT NULL DEFAULT '22:00:00',
  night_end TIME NOT NULL DEFAULT '06:00:00',
  rate_extended DECIMAL(4,2) NOT NULL DEFAULT 1.50,
  rate_night DECIMAL(4,2) NOT NULL DEFAULT 0.50,
  rate_holiday DECIMAL(4,2) NOT NULL DEFAULT 1.50,
  rate_holiday_over DECIMAL(4,2) NOT NULL DEFAULT 2.00,
  rate_public_holiday DECIMAL(4,2) NOT NULL DEFAULT 1.50,
  rate_labor_day DECIMAL(4,2) NOT NULL DEFAULT 1.50,
  -- 휴일근로에서 '초과' 가산율로 넘어가는 기준 시간
  holiday_over_hours INT NOT NULL DEFAULT 8,
  -- 주 최대 근로시간(소정 40 + 연장 12). 넘으면 막지 않고 경고만 한다.
  weekly_limit_hours INT NOT NULL DEFAULT 52,
  -- 통상시급 = 연차수당 1일 단가 ÷ 이 값
  daily_standard_hours INT NOT NULL DEFAULT 8,
  updated_by BIGINT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO overtime_settings(id) VALUES (1);

-- ── 조별 기준 ───────────────────────────────────────────────
-- 시차출근 조(A/B/C…)마다 연장 기준과 포괄임금 인정 시간이 다르다.
-- group_code 'DEFAULT' 는 조가 없는 기본 근무자다. 행이 없는 조는 DEFAULT 를 따른다.
--   extended_after_minutes  : 하루 실근로가 이 분을 넘은 부분부터 연장으로 본다(기본 8시간).
--   inclusive_monthly_minutes : 급여에 이미 포함된 월 시간외 시간(포괄임금). 이만큼은 추가 지급 대상에서 뺀다.
CREATE TABLE IF NOT EXISTS overtime_group_rules (
  group_code VARCHAR(30) PRIMARY KEY,
  extended_after_minutes INT NOT NULL DEFAULT 480,
  inclusive_monthly_minutes INT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO overtime_group_rules(group_code) VALUES ('DEFAULT');

-- ── 시간외 근무 신청서 ──────────────────────────────────────
-- work_date 는 그 근무가 속한 날이다. 자정을 넘긴 야근은 end_at 이 다음 날이어도 work_date 는 전날이다.
-- request_kind : PRE(사전 신청) / POST(사후 신청 — 이미 한 근무를 증적으로 올림)
-- day_type     : WORKDAY / HOLIDAY(휴무 요일·회사 휴무) / PUBLIC_HOLIDAY / LABOR_DAY — 신청 시점에 판정해 고정한다.
-- *_minutes    : 신청 시간 기준 구간별 분. 실제 출퇴근 기록과 맞춘 인정 시간은 승인 뒤 집계 단계에서 다시 계산한다.
CREATE TABLE IF NOT EXISTS overtime_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  work_date DATE NOT NULL,
  start_at DATETIME NOT NULL,
  end_at DATETIME NOT NULL,
  break_minutes INT NOT NULL DEFAULT 0,
  request_kind VARCHAR(10) NOT NULL DEFAULT 'PRE',
  day_type VARCHAR(20) NOT NULL DEFAULT 'WORKDAY',
  total_minutes INT NOT NULL DEFAULT 0,
  night_minutes INT NOT NULL DEFAULT 0,
  reason VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  current_approval_step INT NOT NULL DEFAULT 1,
  decided_by BIGINT NULL,
  rejected_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY ix_overtime_requests_user_date (user_id, work_date),
  CONSTRAINT fk_overtime_requests_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 출퇴근 기록의 출처 ──────────────────────────────────────
-- 웹 버튼(WEB)·윈도우 앱(DESKTOP)·외부 출입 단말(DEVICE, 예: 경비업체 지문 인식)·정정 승인(CORRECTION).
-- 시간외 계산은 출처와 무관하게 기록된 시각만 본다. 단말 연동이 들어와도 계산 쪽은 바뀌지 않게 하려는 자리다.
SET @has_source = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'attendance_records' AND COLUMN_NAME = 'source');
SET @sql = IF(@has_source = 0,
              'ALTER TABLE attendance_records ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT ''WEB'' AFTER note',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 결재경로 시드 ───────────────────────────────────────────
-- 경로가 없으면 신청 자체가 막힌다. 시차출근 경로를 복사해 첫 운영을 막지 않는다.
-- 결재경로 관리 화면에서 '시간외근무' 탭으로 바꿀 수 있다.
INSERT IGNORE INTO approval_route_rules(document_type, requester_level, step_order, approver_type, approver_user_id, active)
SELECT 'OVERTIME', requester_level, step_order, approver_type, approver_user_id, active
FROM approval_route_rules WHERE document_type = 'FLEX_TIME';
