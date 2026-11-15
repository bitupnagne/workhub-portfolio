-- 인사평가 개편: 팀 KPI를 평가 회차에서 분리해 '연도(eval_year)' 종속으로 전환
--  - 수립시기 = 매년 1분기(회차와 무관, 연도 1팀 1KPI). 회차는 같은 연도 KPI를 참조해 실적/채점에 사용.
--  - 담당자 실적 '승인/승인취소' 단계(RESULT_APPROVED) 신설 — 승인 후 팀장 수정 잠금, 승인취소 시 재수정.
--  - 위원회 검토용 '실적 근거자료(증빙)' 첨부 신설.
-- 상태 흐름: DRAFT → SUBMITTED → CONFIRMED(수립 확정) → RESULT_SUBMITTED(팀장 실적+증빙 제출)
--            → RESULT_APPROVED(담당자 승인, 평가위원회 채점 대상)

ALTER TABLE eval_team_kpis
  ADD COLUMN eval_year INT NULL AFTER id,
  ADD COLUMN result_approved_at DATETIME NULL AFTER result_submitted_at,
  ADD COLUMN approved_by BIGINT NULL AFTER result_approved_at;

-- 기존 데이터: 회차의 연도로 backfill
UPDATE eval_team_kpis k
  JOIN eval_cycles c ON c.id = k.cycle_id
  SET k.eval_year = c.eval_year
  WHERE k.eval_year IS NULL;

-- 회차 강결합 완화: 기존 FK·UNIQUE 제거, cycle_id nullable(레거시 보존)
ALTER TABLE eval_team_kpis DROP FOREIGN KEY IF EXISTS fk_eval_kpi_cycle;
ALTER TABLE eval_team_kpis DROP INDEX IF EXISTS uk_eval_kpi_cycle_team;
ALTER TABLE eval_team_kpis MODIFY COLUMN cycle_id BIGINT NULL;

-- 연도 단위 1팀 1KPI
ALTER TABLE eval_team_kpis MODIFY COLUMN eval_year INT NOT NULL;
ALTER TABLE eval_team_kpis ADD UNIQUE KEY uk_eval_kpi_year_team (eval_year, team_id);

-- 실적 근거자료(증빙) 첨부
CREATE TABLE eval_kpi_evidence (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  kpi_id BIGINT NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  stored_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(120) NULL,
  file_size BIGINT NULL,
  uploaded_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_eval_evd_kpi FOREIGN KEY (kpi_id) REFERENCES eval_team_kpis(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
