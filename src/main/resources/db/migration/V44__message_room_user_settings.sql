CREATE TABLE message_room_user_settings (
  room_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  custom_title VARCHAR(160) NULL,
  pinned BOOLEAN NOT NULL DEFAULT FALSE,
  muted BOOLEAN NOT NULL DEFAULT FALSE,
  opacity_percent INT NOT NULL DEFAULT 100,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (room_id, user_id),
  CONSTRAINT fk_message_room_user_settings_room FOREIGN KEY (room_id) REFERENCES message_rooms(id) ON DELETE CASCADE,
  CONSTRAINT fk_message_room_user_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT chk_message_room_user_settings_opacity CHECK (opacity_percent BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_message_room_user_settings_user_pinned
  ON message_room_user_settings(user_id, pinned, updated_at);
