-- HR 결재선/우측 직원목록 기준 조직장 정합화.
-- 시스템 계정은 우측 직원목록에서 숨기므로 실제 운영 조직장으로 쓰지 않는다.

-- 빈 DB에서 적용 시 아래 UPDATE 들이 참조하는 team_id 행이 사전에 존재하도록 보강.
-- (idempotent: 이미 있으면 INSERT IGNORE로 skip)
INSERT IGNORE INTO teams (id, code, name, parent_id, node_type, display_order, active) VALUES
(123, 'DEV_VENDOR', '플랫폼개발팀_용역', 5, 'TEAM', 99, 1);

UPDATE teams SET leader_user_id = 903 WHERE code = 'CEO';
UPDATE teams SET leader_user_id = 401 WHERE code = 'MGT_HQ';
UPDATE teams SET leader_user_id = 502 WHERE code = 'IT_HQ';

UPDATE teams SET leader_user_id = 101 WHERE code = 'FIN';
UPDATE teams SET leader_user_id = 201 WHERE code = 'STR';
UPDATE teams SET leader_user_id = 301 WHERE code = 'TRA';
UPDATE teams SET leader_user_id = 403 WHERE code = 'BIZ';
UPDATE teams SET leader_user_id = 501 WHERE code = 'DEV';
UPDATE teams SET leader_user_id = 601 WHERE code = 'SEC';
UPDATE teams SET leader_user_id = 701 WHERE code = 'OPS';
UPDATE teams SET leader_user_id = 801 WHERE code = 'AUD';
UPDATE teams SET leader_user_id = 901 WHERE code = 'SUP';
UPDATE teams SET leader_user_id = 902 WHERE code = 'CALL';
UPDATE teams SET leader_user_id = 503 WHERE code = 'DEV-OPR';

UPDATE users SET team_id = 100, dept = '대표이사', job_title = '대표이사' WHERE id = 903;
UPDATE users SET team_id = 110, dept = '운영본부', job_title = '본부장' WHERE id = 401;
UPDATE users SET team_id = 120, dept = '기술본부', job_title = '본부장' WHERE id = 502;

UPDATE users SET team_id = 10, dept = '고객상담센터', job_title = '고객상담센터장' WHERE id = 902;
UPDATE users SET team_id = 123, dept = '플랫폼개발팀_용역', job_title = '용역관리 책임자' WHERE id = 503;

UPDATE users SET dept = '운영지원팀', job_title = '팀장' WHERE id = 101;
UPDATE users SET dept = '제품전략팀', job_title = '팀장' WHERE id = 201;
UPDATE users SET dept = '서비스기획팀', job_title = '팀장' WHERE id = 301;
UPDATE users SET dept = '사업관리팀', job_title = '팀장' WHERE id = 403;
UPDATE users SET dept = '플랫폼개발팀', job_title = '팀장' WHERE id = 501;
UPDATE users SET dept = '보안관리팀', job_title = '팀장' WHERE id = 601;
UPDATE users SET dept = '서비스운영팀', job_title = '팀장' WHERE id = 701;
UPDATE users SET dept = '품질관리팀', job_title = '팀장' WHERE id = 801;
UPDATE users SET dept = '고객지원팀', job_title = '팀장' WHERE id = 901;

DELETE ur
FROM user_roles ur
LEFT JOIN teams t ON t.leader_user_id = ur.user_id
                 AND t.node_type = 'TEAM'
                 AND t.active = TRUE
WHERE ur.role_code = 'TEAM_LEADER'
  AND t.id IS NULL;

INSERT IGNORE INTO user_roles(user_id, role_code) VALUES
(101, 'TEAM_LEADER'), (201, 'TEAM_LEADER'), (301, 'TEAM_LEADER'),
(403, 'TEAM_LEADER'), (501, 'TEAM_LEADER'), (601, 'TEAM_LEADER'),
(701, 'TEAM_LEADER'), (801, 'TEAM_LEADER'), (901, 'TEAM_LEADER'),
(902, 'TEAM_LEADER'), (503, 'TEAM_LEADER');

INSERT IGNORE INTO approval_route_rules(document_type, requester_level, step_order, approver_type, approver_user_id, active) VALUES
('LEAVE', 'CEO', 1, 'SELF', NULL, TRUE),
('TRIP', 'CEO', 1, 'SELF', NULL, TRUE);
