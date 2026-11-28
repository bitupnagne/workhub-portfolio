-- 결재 도장 그리드 1행에 직책(팀장/본부장/대표이사 등)을 표시하기 위해
-- 결재 단계가 생성될 때의 결재선 유형을 단계에 함께 보존한다.
ALTER TABLE approval_steps
  ADD COLUMN approver_type VARCHAR(30) NULL AFTER approver_user_id;
