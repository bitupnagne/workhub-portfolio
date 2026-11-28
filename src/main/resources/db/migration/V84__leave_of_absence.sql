-- [B7] 휴직 — 사유(종류)와 신청·결재, 그리고 연차 출근율 반영.
--
-- 지금까지 휴직은 users.loa_start/loa_end 두 날짜뿐이었고, 인사담당자가 조직관리에서
-- 직접 넣는 것 말고는 경로가 없었다. 사유도 없어서 <육아휴직인지 개인 사정인지>를
-- 코드가 알 방법이 없었고, 그래서 연차 부여에 전혀 반영되지 못했다 —
-- 1년 내내 휴직한 사람도 근속만 쌓여 연차가 그대로 나왔고 그게 수당 정산까지 갔다.

-- ── 휴직 종류 ────────────────────────────────────────────────
-- accrual_treatment 이 이 표의 핵심이다. 근로기준법 제60조의 출근율(80%) 계산에서
-- 휴직 기간을 어떻게 볼 것인가를 정한다.
--
--   COUNTED_AS_WORK : 출근한 것으로 본다. 육아휴직·업무상 부상/질병이 여기 해당하며
--                     법이 출근으로 간주하도록 정하고 있어 연차가 깎이지 않는다.
--   EXCLUDED        : 소정근로일에서 빼고 <남은 기간에 비례해> 부여한다.
--                     개인 사정 휴직 등 통상의 경우다.
--
-- 종류를 표로 둔 이유는 회사마다 운영이 다르기 때문이다. 코드에 박아 두면
-- '질병휴직을 출근 간주로 바꾼다' 같은 결정에 배포가 필요해진다.
CREATE TABLE IF NOT EXISTS loa_types (
  code VARCHAR(30) PRIMARY KEY,
  label VARCHAR(50) NOT NULL,
  accrual_treatment VARCHAR(20) NOT NULL,
  evidence_required BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO loa_types(code, label, accrual_treatment, evidence_required, sort_order) VALUES
  ('PARENTAL',  '육아휴직',        'COUNTED_AS_WORK', TRUE,  1),
  ('INJURY',    '업무상 부상·질병', 'COUNTED_AS_WORK', TRUE,  2),
  ('MILITARY',  '병역',            'EXCLUDED',        TRUE,  3),
  ('MEDICAL',   '질병휴직',        'EXCLUDED',        TRUE,  4),
  ('PERSONAL',  '개인사정 휴직',    'EXCLUDED',        FALSE, 5),
  ('OTHER',     '기타',            'EXCLUDED',        FALSE, 6);

-- ── 휴직 신청서 ──────────────────────────────────────────────
-- 다른 신청서(휴가·출장·시차출근)와 같은 모양이다 — status/current_approval_step 으로
-- approval_steps 와 물린다. end_date 가 NULL 이면 복직일 미정이다.
CREATE TABLE IF NOT EXISTS loa_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(30) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  current_approval_step INT NOT NULL DEFAULT 1,
  decided_by BIGINT NULL,
  rejected_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY ix_loa_requests_user_year (user_id, start_date),
  CONSTRAINT fk_loa_requests_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_loa_requests_type FOREIGN KEY (type) REFERENCES loa_types(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 현재 휴직의 사유 ─────────────────────────────────────────
-- users.loa_start/loa_end 는 그대로 두고 사유만 덧붙인다. 기존 데이터는 사유가 없으므로
-- NULL 이며, 연차 계산에서는 '알 수 없음'을 EXCLUDED 로 보지 않고 <반영하지 않는다> —
-- 사유를 모른 채 연차를 깎으면 육아휴직자가 손해를 본다.
SET @has_type = (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'loa_type');
SET @sql = IF(@has_type = 0,
              'ALTER TABLE users ADD COLUMN loa_type VARCHAR(30) NULL AFTER loa_end',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 휴직 결재경로 시드 ───────────────────────────────────────
-- 경로가 없으면 신청 자체가 막힌다("결재경로가 설정되어 있지 않습니다").
-- 시차출근(FLEX_TIME) 경로를 그대로 복사해 첫 운영을 막지 않는다. 관리자가 결재경로
-- 관리 화면에서 바꿀 수 있다(ApprovalDocumentType 을 순회하므로 자동으로 나타난다).
INSERT IGNORE INTO approval_route_rules(document_type, requester_level, step_order, approver_type, approver_user_id, active)
SELECT 'LOA', requester_level, step_order, approver_type, approver_user_id, active
FROM approval_route_rules WHERE document_type = 'FLEX_TIME';
