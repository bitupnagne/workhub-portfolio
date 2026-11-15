ALTER TABLE message_messages
  DROP FOREIGN KEY fk_message_messages_sender;

ALTER TABLE message_messages
  MODIFY sender_user_id BIGINT NULL;

ALTER TABLE message_messages
  ADD CONSTRAINT fk_message_messages_sender
  FOREIGN KEY (sender_user_id) REFERENCES users(id) ON DELETE SET NULL;
