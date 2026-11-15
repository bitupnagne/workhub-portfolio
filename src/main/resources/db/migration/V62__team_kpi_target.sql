-- 팀 KPI(업적평가) 작성 대상 팀 플래그. 기존 동작 보존 위해 기본 TRUE.
-- 주간보고 대상(weekly_report_target)과 동일한 패턴.
ALTER TABLE teams ADD COLUMN kpi_target BOOLEAN NOT NULL DEFAULT TRUE;

-- 도급만 있는 팀(활성·비시스템 멤버가 있으나 그중 비도급이 0명)은 KPI 대상에서 제외.
UPDATE teams t SET kpi_target = FALSE
WHERE t.node_type = 'TEAM'
  AND EXISTS (
      SELECT 1 FROM users u
      WHERE u.team_id = t.id AND u.active = TRUE AND COALESCE(u.system_account, FALSE) = FALSE
  )
  AND NOT EXISTS (
      SELECT 1 FROM users u
      WHERE u.team_id = t.id AND u.active = TRUE
        AND COALESCE(u.system_account, FALSE) = FALSE
        AND COALESCE(u.contractor, FALSE) = FALSE
  );
