ALTER TABLE approval_route_rules
  ADD COLUMN receiver_team_id BIGINT NULL AFTER approver_user_id,
  ADD CONSTRAINT fk_approval_route_receiver_team FOREIGN KEY (receiver_team_id) REFERENCES teams(id) ON DELETE SET NULL;

CREATE TABLE approval_document_receivers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  document_type VARCHAR(20) NOT NULL,
  document_id BIGINT NOT NULL,
  receiver_team_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_approval_document_receiver (document_type, document_id, receiver_team_id),
  INDEX idx_approval_document_receiver_team (receiver_team_id, document_type),
  CONSTRAINT fk_approval_document_receiver_team FOREIGN KEY (receiver_team_id) REFERENCES teams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
