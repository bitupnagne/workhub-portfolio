ALTER TABLE approval_route_rules
    MODIFY document_type VARCHAR(40) NOT NULL;

ALTER TABLE approval_steps
    MODIFY document_type VARCHAR(40) NOT NULL;

ALTER TABLE approval_document_receivers
    MODIFY document_type VARCHAR(40) NOT NULL;

-- 팀장 역할만 있고 조직도 leader_user_id가 비어 있는 팀을 보정한다.
UPDATE teams t
JOIN (
    SELECT u.team_id, MIN(u.id) AS leader_user_id, COUNT(*) AS leader_count
    FROM users u
    JOIN user_roles ur ON ur.user_id = u.id AND ur.role_code = 'TEAM_LEADER'
    WHERE u.active = TRUE
      AND u.team_id IS NOT NULL
    GROUP BY u.team_id
) leader ON leader.team_id = t.id AND leader.leader_count = 1
SET t.leader_user_id = leader.leader_user_id
WHERE t.active = TRUE
  AND t.node_type = 'TEAM'
  AND t.leader_user_id IS NULL;

-- 시차출근/근태정정은 HR 문서이므로 휴가/출장과 같은 기본 결재 체인을 제공한다.
INSERT IGNORE INTO approval_route_rules(document_type, requester_level, step_order, approver_type, approver_user_id, active) VALUES
('FLEX_TIME', 'MEMBER', 1, 'TEAM_LEADER', NULL, TRUE),
('FLEX_TIME', 'MEMBER', 2, 'DIVISION_LEADER', NULL, TRUE),
('FLEX_TIME', 'TEAM_LEADER', 1, 'DIVISION_LEADER', NULL, TRUE),
('FLEX_TIME', 'DIVISION_LEADER', 1, 'ROOT_LEADER', NULL, TRUE),
('FLEX_TIME', 'CEO', 1, 'SELF', NULL, TRUE),
('ATTENDANCE_CORRECTION', 'MEMBER', 1, 'TEAM_LEADER', NULL, TRUE),
('ATTENDANCE_CORRECTION', 'MEMBER', 2, 'DIVISION_LEADER', NULL, TRUE),
('ATTENDANCE_CORRECTION', 'TEAM_LEADER', 1, 'DIVISION_LEADER', NULL, TRUE),
('ATTENDANCE_CORRECTION', 'DIVISION_LEADER', 1, 'ROOT_LEADER', NULL, TRUE),
('ATTENDANCE_CORRECTION', 'CEO', 1, 'SELF', NULL, TRUE);
