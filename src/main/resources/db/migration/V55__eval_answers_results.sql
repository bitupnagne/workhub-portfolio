-- 인사평가 P4+P5: 평가 답변(문항/협조도) + 평가위원 채점 + 최종 결과
-- eval_answers: 역량/다면 문항별 채점(0~문항 배점)
CREATE TABLE eval_answers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  assignment_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_answer (assignment_id, item_id),
  CONSTRAINT fk_eval_answer_asgn FOREIGN KEY (assignment_id) REFERENCES eval_assignments(id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_answer_item FOREIGN KEY (item_id) REFERENCES eval_template_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- eval_coop_scores: 협조도 팀 매트릭스 평정(S=10/A=8/B=6/C=4/D=2)
CREATE TABLE eval_coop_scores (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  assignment_id BIGINT NOT NULL,
  target_team_id BIGINT NOT NULL,
  grade CHAR(1) NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_coop (assignment_id, target_team_id),
  CONSTRAINT fk_eval_coop_asgn FOREIGN KEY (assignment_id) REFERENCES eval_assignments(id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_coop_team FOREIGN KEY (target_team_id) REFERENCES teams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- eval_kpi_scores: 평가위원의 팀 KPI 항목별 채점(0~100, 항목 최종점수 = 위원 평균)
CREATE TABLE eval_kpi_scores (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  kpi_item_id BIGINT NOT NULL,
  scorer_user_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_kpi_score (kpi_item_id, scorer_user_id),
  CONSTRAINT fk_eval_kscore_item FOREIGN KEY (kpi_item_id) REFERENCES eval_team_kpi_items(id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_kscore_user FOREIGN KEY (scorer_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- eval_results: 개인별 최종 집계(관리자 수락 시 ACCEPTED 확정, 피평가자 비공개)
CREATE TABLE eval_results (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  cycle_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  eval_group VARCHAR(10) NOT NULL,        -- LEADER / G12 / G34 / G57
  team_name VARCHAR(100) NULL,
  perf_score DECIMAL(6,2) NULL,           -- 업적 반영점(최대 40/60)
  comp_score DECIMAL(6,2) NULL,           -- 역량 반영점(최대 55/60/35)
  multi_score DECIMAL(6,2) NULL,          -- 다면 반영점(최대 5, 직책자만)
  training_score DECIMAL(6,2) NULL,       -- 연수 반영점(최대 5, 3~7급)
  total_score DECIMAL(6,2) NULL,
  detail_json MEDIUMTEXT NULL,            -- 산출 근거(감사 대응)
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT / ACCEPTED
  accepted_at DATETIME NULL,
  accepted_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_result (cycle_id, user_id),
  CONSTRAINT fk_eval_result_cycle FOREIGN KEY (cycle_id) REFERENCES eval_cycles(id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_result_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
