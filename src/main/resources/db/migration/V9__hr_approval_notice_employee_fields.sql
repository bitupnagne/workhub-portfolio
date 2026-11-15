ALTER TABLE users
  ADD COLUMN employee_number VARCHAR(40) NULL AFTER username,
  ADD COLUMN hire_date DATE NULL AFTER email;

UPDATE users
SET employee_number = CONCAT('EMP-', LPAD(id, 5, '0'))
WHERE employee_number IS NULL;

CREATE UNIQUE INDEX uk_users_employee_number ON users(employee_number);

ALTER TABLE business_trips
  ADD COLUMN transport_cost DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER purpose,
  ADD COLUMN lodging_cost DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER transport_cost,
  ADD COLUMN meal_cost DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER lodging_cost,
  ADD COLUMN daily_allowance DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER meal_cost,
  ADD COLUMN etc_cost DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER daily_allowance,
  ADD COLUMN cost_note VARCHAR(500) NULL AFTER etc_cost;

ALTER TABLE leave_requests
  ADD COLUMN current_approval_step INT NULL AFTER status;

ALTER TABLE business_trips
  ADD COLUMN current_approval_step INT NULL AFTER status;

CREATE TABLE approval_route_rules (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  document_type VARCHAR(20) NOT NULL,
  requester_level VARCHAR(30) NOT NULL,
  step_order INT NOT NULL,
  approver_type VARCHAR(30) NOT NULL,
  approver_user_id BIGINT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_approval_route_rule (document_type, requester_level, step_order),
  CONSTRAINT fk_approval_route_user FOREIGN KEY (approver_user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_delegations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  approver_user_id BIGINT NOT NULL,
  delegate_user_id BIGINT NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  reason VARCHAR(500) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_approval_delegations_user_date (approver_user_id, start_date, end_date, active),
  CONSTRAINT fk_approval_delegate_owner FOREIGN KEY (approver_user_id) REFERENCES users(id),
  CONSTRAINT fk_approval_delegate_user FOREIGN KEY (delegate_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_steps (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  document_type VARCHAR(20) NOT NULL,
  document_id BIGINT NOT NULL,
  step_order INT NOT NULL,
  approver_user_id BIGINT NOT NULL,
  delegate_user_id BIGINT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  acted_by BIGINT NULL,
  acted_at DATETIME NULL,
  rejected_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_approval_step_document (document_type, document_id, step_order),
  INDEX idx_approval_steps_approver (approver_user_id, delegate_user_id, status),
  CONSTRAINT fk_approval_steps_approver FOREIGN KEY (approver_user_id) REFERENCES users(id),
  CONSTRAINT fk_approval_steps_delegate FOREIGN KEY (delegate_user_id) REFERENCES users(id),
  CONSTRAINT fk_approval_steps_actor FOREIGN KEY (acted_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_notices (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(160) NOT NULL,
  body TEXT NOT NULL,
  link_url VARCHAR(300) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NOT NULL,
  updated_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_admin_notices_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT fk_admin_notices_updater FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE notifications
  ADD COLUMN notice_id BIGINT NULL AFTER id,
  ADD CONSTRAINT fk_notifications_notice FOREIGN KEY (notice_id) REFERENCES admin_notices(id) ON DELETE SET NULL;

CREATE INDEX idx_admin_notices_active ON admin_notices(active, created_at);
CREATE INDEX idx_notifications_notice ON notifications(notice_id);

INSERT INTO approval_route_rules(document_type, requester_level, step_order, approver_type, approver_user_id) VALUES
('LEAVE', 'MEMBER', 1, 'TEAM_LEADER', NULL),
('LEAVE', 'TEAM_LEADER', 1, 'DIVISION_LEADER', NULL),
('LEAVE', 'DIVISION_LEADER', 1, 'ROOT_LEADER', NULL),
('TRIP', 'MEMBER', 1, 'TEAM_LEADER', NULL),
('TRIP', 'MEMBER', 2, 'DIVISION_LEADER', NULL),
('TRIP', 'TEAM_LEADER', 1, 'DIVISION_LEADER', NULL),
('TRIP', 'DIVISION_LEADER', 1, 'ROOT_LEADER', NULL)
ON DUPLICATE KEY UPDATE approver_type = VALUES(approver_type);
