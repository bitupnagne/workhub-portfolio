CREATE TABLE report_week_locks (
    week_key VARCHAR(10) PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE report_items
    ADD COLUMN source_project_id BIGINT NULL,
    ADD COLUMN source_task_id BIGINT NULL,
    ADD COLUMN source_team_id BIGINT NULL,
    ADD COLUMN source_project_name VARCHAR(160) NULL,
    ADD COLUMN source_activity_id BIGINT NULL,
    ADD COLUMN project_progress INT NULL,
    ADD COLUMN project_summary TEXT NULL,
    ADD COLUMN project_note TEXT NULL,
    ADD UNIQUE KEY uk_report_project_task (weekly_report_id, source_task_id),
    ADD KEY idx_report_project_team (source_team_id, weekly_report_id);
