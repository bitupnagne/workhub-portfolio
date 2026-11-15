ALTER TABLE approval_document_snapshots
  ADD COLUMN stored_filename VARCHAR(255) NULL AFTER snapshot_html,
  ADD COLUMN original_filename VARCHAR(255) NULL AFTER stored_filename,
  ADD COLUMN content_type VARCHAR(100) NULL AFTER original_filename,
  ADD COLUMN file_size BIGINT NULL AFTER content_type;

CREATE TABLE company_schedules (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(200) NOT NULL,
  description TEXT NULL,
  start_at DATETIME NOT NULL,
  end_at DATETIME NOT NULL,
  color VARCHAR(20) NOT NULL DEFAULT '#2563eb',
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_company_schedules_range (start_at, end_at),
  CONSTRAINT fk_company_schedules_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
);
