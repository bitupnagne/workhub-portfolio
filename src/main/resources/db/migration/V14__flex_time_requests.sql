CREATE TABLE flex_time_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  shift_group VARCHAR(1) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  current_approval_step INT NULL,
  approver_id BIGINT NULL,
  approved_at DATETIME NULL,
  rejected_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_flex_time_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_flex_time_approver FOREIGN KEY (approver_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_flex_time_user_date ON flex_time_requests(user_id, start_date, end_date);
CREATE INDEX idx_flex_time_status ON flex_time_requests(status);
