-- 결재 단계에서 특정 조직의 현재 조직장을 참조할 수 있게 한다.
-- 예: 신청자 소속 본부장 이후 사업관리팀장, 운영본부장처럼 고정 조직의 조직장을 단계로 지정.
ALTER TABLE approval_route_rules
  ADD COLUMN approver_team_id BIGINT NULL AFTER approver_user_id,
  ADD CONSTRAINT fk_approval_route_approver_team
    FOREIGN KEY (approver_team_id) REFERENCES teams(id) ON DELETE SET NULL;
