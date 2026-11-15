CREATE TABLE attendance_settings (
  id TINYINT PRIMARY KEY,
  default_start_time TIME NOT NULL DEFAULT '09:00:00',
  default_end_time TIME NOT NULL DEFAULT '18:00:00',
  updated_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_attendance_settings_user FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO attendance_settings(id, default_start_time, default_end_time)
VALUES(1, '09:00:00', '18:00:00')
ON DUPLICATE KEY UPDATE id = id;

CREATE TABLE attendance_day_overrides (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_date DATE NOT NULL UNIQUE,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  note VARCHAR(255) NULL,
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_attendance_day_overrides_user FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE company_holidays (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  holiday_date DATE NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_company_holidays_user FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_trips (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  destination VARCHAR(150) NOT NULL,
  purpose VARCHAR(500) NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  approver_id BIGINT NULL,
  approved_at DATETIME NULL,
  rejected_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_business_trips_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_business_trips_approver FOREIGN KEY (approver_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_attendance_overrides_date ON attendance_day_overrides(work_date);
CREATE INDEX idx_company_holidays_date ON company_holidays(holiday_date);
CREATE INDEX idx_business_trips_user_date ON business_trips(user_id, start_date, end_date);
CREATE INDEX idx_business_trips_status ON business_trips(status);