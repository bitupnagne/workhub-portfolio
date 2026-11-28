-- [OW] 외근 신청 — 평소 외근에는 쓰지 않는다. 외근 나갔다가 회사로 돌아오지 않을 때 올려서 그날 퇴근을 처리한다.
--   승인되면 퇴근을 찍지 않은 날은 '외근 종료 시각'을 퇴근으로, 상태는 외근(OUTSIDE)으로 기록한다.
--   조퇴·퇴근 누락으로 보지 않는다. 이미 퇴근을 찍은 날은 근태를 덮어쓰지 않고 문서만 남긴다.
CREATE TABLE IF NOT EXISTS outside_work_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  work_date DATE NOT NULL,
  out_time TIME NOT NULL,
  end_time TIME NOT NULL,
  place VARCHAR(200) NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  current_approval_step INT NOT NULL DEFAULT 1,
  decided_by BIGINT NULL,
  rejected_reason VARCHAR(500) NULL,
  attendance_applied BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY ix_outside_work_user_date (user_id, work_date),
  CONSTRAINT fk_outside_work_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 첫 결재경로는 시차출근 경로를 복사한다 — 이번에는 수신 부서·지정 부서 결재자까지 함께(V88 에서 빠뜨렸던 것).
INSERT IGNORE INTO approval_route_rules(document_type, scope_team_id, requester_level, step_order, approver_type,
                                        approver_user_id, approver_team_id, receiver_team_id, active)
SELECT 'OUTSIDE_WORK', scope_team_id, requester_level, step_order, approver_type,
       approver_user_id, approver_team_id, receiver_team_id, active
FROM approval_route_rules WHERE document_type = 'FLEX_TIME';

INSERT IGNORE INTO approval_route_receivers(rule_id, receiver_team_id)
SELECT o.id, rr.receiver_team_id
FROM approval_route_rules o
JOIN approval_route_rules f
  ON f.document_type = 'FLEX_TIME'
 AND f.requester_level = o.requester_level
 AND f.step_order = o.step_order
 AND f.scope_team_id <=> o.scope_team_id
JOIN approval_route_receivers rr ON rr.rule_id = f.id
WHERE o.document_type = 'OUTSIDE_WORK';

-- [DLG] 대리승인 지정을 부재 신청(휴가·출장·외근·휴직)에 묶는다.
--   신청할 때 '예정'(active=FALSE)으로 만들고, 신청이 최종 승인되면 적용(active=TRUE), 반려·회수·취소되면 지운다.
--   source_type/source_id 가 없는 행은 신청 없이 직접 지정한 것이다.
SET @has_source = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'approval_delegations' AND COLUMN_NAME = 'source_type');
SET @sql = IF(@has_source = 0,
              'ALTER TABLE approval_delegations ADD COLUMN source_type VARCHAR(20) NULL AFTER reason, ADD COLUMN source_id BIGINT NULL AFTER source_type, ADD KEY idx_approval_delegations_source (source_type, source_id)',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
