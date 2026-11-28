-- 협조도 수동입력: 팀 KPI 단위 토글(coop_manual) + 항목별 수동 평가점(manual_score).
-- 토글 OFF=협조도 평정 평균×10 자동 산입, ON=manual_score 사용. 자동·수동 둘 다 보존(토글로 결정).
-- 검토해제 시 manual_score는 초기화(서비스에서 처리).
ALTER TABLE eval_team_kpis ADD COLUMN IF NOT EXISTS coop_manual BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE eval_team_kpi_items ADD COLUMN IF NOT EXISTS manual_score DECIMAL(6,2) NULL;
