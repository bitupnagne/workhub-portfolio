CREATE TABLE leave_balances (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  balance_year INT NOT NULL,
  total_days DECIMAL(5,2) NOT NULL DEFAULT 15.00,
  used_days DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  adjusted_days DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  note VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_leave_balances_user_year (user_id, balance_year),
  CONSTRAINT fk_leave_balances_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE leave_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(30) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  days DECIMAL(5,2) NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  approver_id BIGINT NULL,
  approved_at DATETIME NULL,
  rejected_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_leave_requests_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_leave_requests_approver FOREIGN KEY (approver_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE attendance_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  work_date DATE NOT NULL,
  check_in_at DATETIME NULL,
  check_out_at DATETIME NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
  note VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_attendance_user_date (user_id, work_date),
  CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE performance_reviews (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  review_year INT NOT NULL,
  review_period VARCHAR(10) NOT NULL,
  user_id BIGINT NOT NULL,
  reviewer_id BIGINT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  score DECIMAL(5,2) NULL,
  summary TEXT NULL,
  goals TEXT NULL,
  comments TEXT NULL,
  submitted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_performance_user_period (user_id, review_year, review_period),
  CONSTRAINT fk_performance_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_performance_reviewer FOREIGN KEY (reviewer_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_leave_requests_user_date ON leave_requests(user_id, start_date, end_date);
CREATE INDEX idx_leave_requests_status ON leave_requests(status);
CREATE INDEX idx_attendance_work_date ON attendance_records(work_date);
CREATE INDEX idx_performance_period ON performance_reviews(review_year, review_period, status);

INSERT INTO leave_balances(user_id, balance_year, total_days, used_days, adjusted_days, note)
SELECT id, YEAR(CURDATE()), 15.00, 0.00, 0.00, '기본 연차'
FROM users
WHERE active = TRUE
ON DUPLICATE KEY UPDATE total_days = total_days;