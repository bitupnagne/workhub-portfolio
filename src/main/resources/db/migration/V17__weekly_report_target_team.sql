-- 주간보고 작성 대상 팀 플래그. 기존 동작 보존을 위해 모든 팀 기본 TRUE.
ALTER TABLE teams ADD COLUMN weekly_report_target BOOLEAN NOT NULL DEFAULT TRUE;
