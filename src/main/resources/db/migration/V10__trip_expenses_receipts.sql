CREATE TABLE business_trip_expenses (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  trip_id BIGINT NOT NULL,
  expense_type VARCHAR(30) NOT NULL,
  description VARCHAR(200) NULL,
  amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  note VARCHAR(300) NULL,
  display_order INT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_business_trip_expenses_trip FOREIGN KEY (trip_id) REFERENCES business_trips(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE business_trip_receipts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  trip_id BIGINT NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  stored_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(80) NOT NULL,
  file_size BIGINT NOT NULL,
  uploaded_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_business_trip_receipts_trip (trip_id),
  UNIQUE KEY uk_business_trip_receipts_stored (stored_filename),
  CONSTRAINT fk_business_trip_receipts_trip FOREIGN KEY (trip_id) REFERENCES business_trips(id) ON DELETE CASCADE,
  CONSTRAINT fk_business_trip_receipts_user FOREIGN KEY (uploaded_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_business_trip_expenses_trip ON business_trip_expenses(trip_id, display_order);
