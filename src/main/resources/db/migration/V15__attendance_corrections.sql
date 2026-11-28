CREATE TABLE attendance_corrections (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  work_date DATE NOT NULL,
  attendance_record_id BIGINT NULL,
  original_check_in_at DATETIME NULL,
  original_check_out_at DATETIME NULL,
  original_status VARCHAR(30) NULL,
  requested_check_in TIME NULL,
  requested_check_out TIME NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  current_approval_step INT NULL,
  approver_id BIGINT NULL,
  approved_at DATETIME NULL,
  rejected_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_att_corr_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_att_corr_record FOREIGN KEY (attendance_record_id) REFERENCES attendance_records(id),
  CONSTRAINT fk_att_corr_approver FOREIGN KEY (approver_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_att_corr_user_date ON attendance_corrections(user_id, work_date);
CREATE INDEX idx_att_corr_status ON attendance_corrections(status);
