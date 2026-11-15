CREATE TABLE message_attachments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  room_id BIGINT NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  stored_filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  file_size BIGINT NOT NULL,
  attachment_type VARCHAR(20) NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_msg_attach_message (message_id),
  INDEX idx_msg_attach_room (room_id),
  INDEX idx_msg_attach_expires (expires_at),
  CONSTRAINT fk_msg_attach_message FOREIGN KEY (message_id) REFERENCES message_messages(id) ON DELETE CASCADE,
  CONSTRAINT fk_msg_attach_room FOREIGN KEY (room_id) REFERENCES message_rooms(id) ON DELETE CASCADE
);
