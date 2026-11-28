CREATE TABLE approval_document_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  document_type VARCHAR(50) NOT NULL,
  document_id BIGINT NOT NULL,
  document_kind VARCHAR(100) NOT NULL,
  document_title VARCHAR(255) NOT NULL,
  requester_name VARCHAR(100),
  team_name VARCHAR(100),
  status_label VARCHAR(50),
  snapshot_html LONGTEXT NOT NULL,
  generated_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_approval_doc_snapshot_doc (document_type, document_id),
  INDEX idx_approval_doc_snapshot_created (created_at),
  CONSTRAINT fk_approval_doc_snapshot_user FOREIGN KEY (generated_by) REFERENCES users(id)
);
