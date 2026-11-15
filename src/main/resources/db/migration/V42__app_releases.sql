CREATE TABLE app_releases (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  version VARCHAR(40) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  stored_file_name VARCHAR(255) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  file_size BIGINT NOT NULL,
  mandatory BOOLEAN NOT NULL DEFAULT FALSE,
  release_note TEXT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_app_releases_version (version),
  KEY idx_app_releases_active (active),
  CONSTRAINT fk_app_releases_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);
