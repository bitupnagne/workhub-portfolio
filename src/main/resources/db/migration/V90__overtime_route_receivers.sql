-- [OT] V88 이 시차출근 결재경로를 시간외근무로 복사할 때 수신 부서(approval_route_receivers)와
-- 지정 부서 결재자(approver_team_id)를 빠뜨렸다. 아직 수신 부서가 하나도 없는 시간외근무 경로에만 채운다
-- (관리자가 이미 손본 경로는 건드리지 않는다).
INSERT IGNORE INTO approval_route_receivers(rule_id, receiver_team_id)
SELECT o.id, rr.receiver_team_id
FROM approval_route_rules o
JOIN approval_route_rules f
  ON f.document_type = 'FLEX_TIME'
 AND f.requester_level = o.requester_level
 AND f.step_order = o.step_order
 AND f.scope_team_id <=> o.scope_team_id
JOIN approval_route_receivers rr ON rr.rule_id = f.id
WHERE o.document_type = 'OVERTIME'
  AND o.id NOT IN (SELECT rule_id FROM (SELECT DISTINCT rule_id FROM approval_route_receivers) has_receiver);

UPDATE approval_route_rules o
JOIN approval_route_rules f
  ON f.document_type = 'FLEX_TIME'
 AND f.requester_level = o.requester_level
 AND f.step_order = o.step_order
 AND f.scope_team_id <=> o.scope_team_id
SET o.approver_team_id = f.approver_team_id
WHERE o.document_type = 'OVERTIME'
  AND o.approver_team_id IS NULL
  AND f.approver_team_id IS NOT NULL
  AND o.approver_type = f.approver_type;
