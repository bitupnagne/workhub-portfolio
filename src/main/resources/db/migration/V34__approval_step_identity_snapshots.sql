ALTER TABLE approval_steps
  ADD COLUMN approver_name_snapshot VARCHAR(100) NULL AFTER approver_type,
  ADD COLUMN approver_dept_snapshot VARCHAR(100) NULL AFTER approver_name_snapshot,
  ADD COLUMN approver_job_title_snapshot VARCHAR(100) NULL AFTER approver_dept_snapshot,
  ADD COLUMN delegate_name_snapshot VARCHAR(100) NULL AFTER delegate_user_id,
  ADD COLUMN acted_by_name_snapshot VARCHAR(100) NULL AFTER acted_by;
