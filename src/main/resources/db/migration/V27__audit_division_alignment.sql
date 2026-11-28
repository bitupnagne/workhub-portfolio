-- 감사 조직도 본부-팀 구조로 정합화하여 HR 결재선의 본부장 단계를 만족시킨다.

INSERT INTO teams(id, code, name, parent_id, node_type, leader_user_id, display_order, active, weekly_report_target)
VALUES (130, 'AUD_HQ', '품질관리본부', 100, 'DIVISION', 801, 30, TRUE, FALSE)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  parent_id = VALUES(parent_id),
  node_type = VALUES(node_type),
  leader_user_id = VALUES(leader_user_id),
  display_order = VALUES(display_order),
  active = VALUES(active),
  weekly_report_target = VALUES(weekly_report_target);

UPDATE teams
SET parent_id = 130,
    leader_user_id = 802,
    display_order = 10
WHERE code = 'AUD';

UPDATE users SET team_id = 130, dept = '품질관리본부', job_title = '본부장' WHERE id = 801;
UPDATE users SET team_id = 8, dept = '품질관리팀', job_title = '팀장' WHERE id = 802;
UPDATE users SET team_id = 8, dept = '품질관리팀', job_title = '감사' WHERE id = 803;

DELETE ur
FROM user_roles ur
LEFT JOIN teams t ON t.leader_user_id = ur.user_id
                 AND t.node_type = 'TEAM'
                 AND t.active = TRUE
WHERE ur.role_code = 'TEAM_LEADER'
  AND t.id IS NULL;

INSERT IGNORE INTO user_roles(user_id, role_code) VALUES
(802, 'TEAM_LEADER');
