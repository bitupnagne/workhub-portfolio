ALTER TABLE users
  ADD COLUMN extension_number VARCHAR(30) NULL AFTER dept,
  ADD COLUMN mobile_number VARCHAR(30) NULL AFTER extension_number,
  ADD COLUMN job_title VARCHAR(160) NULL AFTER mobile_number,
  ADD COLUMN email VARCHAR(160) NULL AFTER job_title;

ALTER TABLE teams
  ADD COLUMN leader_user_id BIGINT NULL AFTER node_type,
  ADD CONSTRAINT fk_teams_leader FOREIGN KEY (leader_user_id) REFERENCES users(id) ON DELETE SET NULL;

CREATE TABLE weekly_report_snapshots (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  week_key VARCHAR(10) NOT NULL,
  snapshot_html MEDIUMTEXT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_weekly_report_snapshots_week (week_key),
  CONSTRAINT fk_weekly_report_snapshots_user FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report_audit_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  week_key VARCHAR(10) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  action VARCHAR(60) NOT NULL,
  description VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_report_audit_logs_week (week_key, created_at),
  CONSTRAINT fk_report_audit_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
