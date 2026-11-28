-- 인사평가 업적평가(팀KPI) 워크플로 개편:
--  1) 연도별 'KPI 수립요청'(캠페인) 분리 — 회차와 무관, 수립기간(시작~종료) 지정. 팀별 개별 검토/확정.
--  2) 검토중 잠금 상태 2개 추가(수립 UNDER_REVIEW / 실적 RESULT_REVIEW) — 담당자 검토 동안 팀장 수정 차단.
--  3) 항목별 '평가점'(score) 컬럼 추가 — 평가위원회 오프라인 채점 결과를 담당자가 수기 입력(업적평가서 양식 컬럼과 1:1).
--
-- 상태 흐름:
--   수립  DRAFT → SUBMITTED →(검토)→ UNDER_REVIEW →(확정)→ CONFIRMED   [검토해제: UNDER_REVIEW→SUBMITTED]
--   실적  CONFIRMED → RESULT_SUBMITTED →(검토)→ RESULT_REVIEW →(확정)→ RESULT_APPROVED  [검토해제: RESULT_REVIEW→RESULT_SUBMITTED]
-- (status VARCHAR(20) 그대로 수용 — 최장 'RESULT_SUBMITTED'=16자)

-- 1) 연도별 수립요청(캠페인)
CREATE TABLE eval_kpi_round (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  eval_year INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- OPEN(수립요청 진행) / CLOSED(마감)
  submit_start_date DATE NULL,                  -- 수립기간 시작
  submit_end_date DATE NULL,                    -- 수립기간 종료(미제출 리마인드 기준)
  requested_by BIGINT NULL,
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  closed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_kpi_round_year (eval_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2) 항목별 평가점(담당자 수기입력) — 달성률 옆 컬럼
ALTER TABLE eval_team_kpi_items
  ADD COLUMN score DECIMAL(6,2) NULL AFTER achievement_rate;
