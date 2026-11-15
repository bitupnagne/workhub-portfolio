ALTER TABLE approval_steps
  ADD COLUMN post_approved_by BIGINT NULL AFTER acted_at,
  ADD COLUMN post_approved_by_name_snapshot VARCHAR(100) NULL AFTER post_approved_by,
  ADD COLUMN post_approved_at DATETIME NULL AFTER post_approved_by_name_snapshot,
  ADD INDEX idx_approval_steps_post_approval (approver_user_id, post_approved_at),
  ADD CONSTRAINT fk_approval_steps_post_approver FOREIGN KEY (post_approved_by) REFERENCES users(id);
