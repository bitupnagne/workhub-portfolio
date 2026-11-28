CREATE TABLE system_settings (
  setting_key VARCHAR(80) NOT NULL,
  setting_value VARCHAR(500) NOT NULL,
  updated_by BIGINT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (setting_key),
  CONSTRAINT fk_system_settings_updated_by FOREIGN KEY (updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO system_settings(setting_key, setting_value)
VALUES
  ('theme.brand', '#4F46E5'),
  ('theme.brandDark', '#4338CA'),
  ('theme.brandDeeper', '#3730A3'),
  ('theme.brandLight', '#EEF2FF'),
  ('theme.brandLight2', '#E0E7FF'),
  ('theme.brandMuted', '#A5B4FC'),
  ('logo.web', ''),
  ('logo.desktop', '')
ON DUPLICATE KEY UPDATE setting_value = setting_value;
