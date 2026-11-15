ALTER TABLE approval_route_rules
  ADD COLUMN scope_team_id BIGINT NULL AFTER document_type;

ALTER TABLE approval_route_rules
  DROP KEY uk_approval_route_rule,
  ADD UNIQUE KEY uk_approval_route_rule (document_type, requester_level, scope_team_id, step_order),
  ADD INDEX idx_approval_route_scope (document_type, requester_level, scope_team_id),
  ADD CONSTRAINT fk_approval_route_scope_team FOREIGN KEY (scope_team_id) REFERENCES teams(id);
