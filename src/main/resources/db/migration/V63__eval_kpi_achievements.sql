-- 팀 KPI 주요실적 다건화: 성과지표(item)별로 N개의 주요실적, 주요실적마다 근거자료(증빙) 첨부.
--  기존: eval_team_kpi_items.achievement(단일 텍스트) + eval_kpi_evidence(kpi 단위)
--  변경: eval_kpi_achievements(item별 다건) + eval_kpi_evidence.achievement_id(주요실적 단위)

CREATE TABLE eval_kpi_achievements (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id BIGINT NOT NULL,
  content TEXT NULL,
  display_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_kpi_ach_item FOREIGN KEY (item_id) REFERENCES eval_team_kpi_items(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 기존 단일 주요실적 → 주요실적 행 1건으로 이관(협조도 행 제외, 내용 있는 것만)
INSERT INTO eval_kpi_achievements(item_id, content, display_order)
SELECT id, achievement, 1
FROM eval_team_kpi_items
WHERE achievement IS NOT NULL AND achievement <> ''
  AND (coop_type IS NULL OR coop_type = '');

-- 증빙을 주요실적에 연결(NULL이면 기존 KPI 단위 증빙 — 레거시 보존)
ALTER TABLE eval_kpi_evidence ADD COLUMN achievement_id BIGINT NULL AFTER kpi_id;
ALTER TABLE eval_kpi_evidence
  ADD CONSTRAINT fk_kpi_evd_ach FOREIGN KEY (achievement_id) REFERENCES eval_kpi_achievements(id) ON DELETE CASCADE;
