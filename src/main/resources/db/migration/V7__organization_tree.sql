ALTER TABLE teams
  ADD COLUMN parent_id BIGINT NULL AFTER name,
  ADD COLUMN node_type VARCHAR(20) NOT NULL DEFAULT 'TEAM' AFTER parent_id;

INSERT INTO teams(id, code, name, parent_id, node_type, display_order, active) VALUES
(100, 'CEO', '대표이사', NULL, 'ROOT', 1, TRUE),
(110, 'MGT_HQ', '운영본부', 100, 'DIVISION', 10, TRUE),
(120, 'IT_HQ', '기술본부', 100, 'DIVISION', 20, TRUE)
ON DUPLICATE KEY UPDATE
  name = VALUES(name), parent_id = VALUES(parent_id), node_type = VALUES(node_type),
  display_order = VALUES(display_order), active = VALUES(active);

INSERT INTO teams(id, code, name, parent_id, node_type, display_order, active) VALUES
(10, 'CALL', '고객상담센터', 9, 'TEAM', 10, TRUE)
ON DUPLICATE KEY UPDATE
  name = VALUES(name), parent_id = VALUES(parent_id), node_type = VALUES(node_type),
  display_order = VALUES(display_order), active = VALUES(active);

UPDATE teams SET parent_id = 110, node_type = 'TEAM', display_order = 10 WHERE code = 'BIZ';
UPDATE teams SET parent_id = 110, node_type = 'TEAM', display_order = 20 WHERE code = 'FIN';
UPDATE teams SET parent_id = 110, node_type = 'TEAM', display_order = 30 WHERE code = 'STR';
UPDATE teams SET parent_id = 110, node_type = 'TEAM', display_order = 40 WHERE code = 'TRA';
UPDATE teams SET parent_id = 110, node_type = 'TEAM', display_order = 50 WHERE code = 'SUP';

UPDATE teams SET parent_id = 120, node_type = 'TEAM', display_order = 10 WHERE code = 'OPS';
UPDATE teams SET parent_id = 120, node_type = 'TEAM', display_order = 20 WHERE code = 'SEC';
UPDATE teams SET parent_id = 120, node_type = 'TEAM', display_order = 30 WHERE code = 'DEV';

UPDATE teams SET parent_id = 100, node_type = 'TEAM', display_order = 30 WHERE code = 'AUD';

DELETE FROM notifications WHERE type = 'MESSAGE';

CREATE INDEX idx_teams_parent ON teams(parent_id, display_order);
ALTER TABLE teams
  ADD CONSTRAINT fk_teams_parent FOREIGN KEY (parent_id) REFERENCES teams(id);