-- 결재 경로별 수신부서 다중 지정용 조인 테이블
CREATE TABLE approval_route_receivers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_id BIGINT NOT NULL,
  receiver_team_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_route_receiver (rule_id, receiver_team_id),
  CONSTRAINT fk_route_receiver_rule FOREIGN KEY (rule_id) REFERENCES approval_route_rules(id) ON DELETE CASCADE,
  CONSTRAINT fk_route_receiver_team FOREIGN KEY (receiver_team_id) REFERENCES teams(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 기존 단일 수신부서 값을 다중 테이블로 이관(동작 보존)
INSERT IGNORE INTO approval_route_receivers(rule_id, receiver_team_id)
SELECT id, receiver_team_id FROM approval_route_rules WHERE receiver_team_id IS NOT NULL;
