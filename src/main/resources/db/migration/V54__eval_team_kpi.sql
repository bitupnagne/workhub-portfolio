-- 인사평가 전면 개편 P3: 팀 KPI(업적평가서) — 팀장 수립 → 관리자 확정 → 연말 실적 작성 → 제출
-- status: DRAFT(팀장 작성중) / SUBMITTED(수립 제출) / CONFIRMED(관리자 확정, 실적 작성 가능) / RESULT_SUBMITTED(실적 제출)
-- 항목 가중치 합 = 100. coop_type 행은 협조도 평정 평균이 평가점으로 자동 산입(P5).
CREATE TABLE eval_team_kpis (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  cycle_id BIGINT NOT NULL,
  team_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  duty_summary TEXT NULL,
  submitted_at DATETIME NULL,
  confirmed_at DATETIME NULL,
  result_submitted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_kpi_cycle_team (cycle_id, team_id),
  CONSTRAINT fk_eval_kpi_cycle FOREIGN KEY (cycle_id) REFERENCES eval_cycles(id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_kpi_team FOREIGN KEY (team_id) REFERENCES teams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE eval_team_kpi_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  kpi_id BIGINT NOT NULL,
  section VARCHAR(10) NOT NULL,      -- QUANT(정량) / QUAL(정성)
  scope VARCHAR(10) NOT NULL,        -- COMPANY(전사 KPI) / TEAM(부서 KPI)
  metric VARCHAR(300) NOT NULL,      -- 성과지표(Index)
  goal TEXT NULL,                    -- 업무목표
  weight DECIMAL(5,2) NOT NULL DEFAULT 0,
  coop_type VARCHAR(20) NULL,        -- COOP_DIV(전사정책 이행 협조도) / COOP_TEAM(부서간 업무 협조도) / NULL
  achievement TEXT NULL,             -- 주요실적(연말)
  achievement_rate VARCHAR(50) NULL, -- 달성율(연말)
  display_order INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_eval_kpi_item FOREIGN KEY (kpi_id) REFERENCES eval_team_kpis(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
