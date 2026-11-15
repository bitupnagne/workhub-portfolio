-- [S-5] 성능 인덱스 보강
-- 기존 인덱스 점검 후 누락분만 안전하게 추가. CREATE INDEX 실패 시 다음 인덱스로 계속 진행할 수 있도록 분리.

-- 1) message_room_members: 미읽음 COUNT 서브쿼리에서 (room_id, last_read_message_id) 자주 사용.
--    PRIMARY(room_id, user_id)는 있지만 last_read_message_id 단일 fk 인덱스만 있어 부족.
CREATE INDEX idx_message_room_members_room_last_read
    ON message_room_members (room_id, last_read_message_id);

-- 2) message_messages: (room_id, id DESC) 형태로 최신 메시지 조회. 기존 (room_id, created_at) 사용 가능하나
--    id 기준 정렬이 더 명확. 결합 인덱스 추가.
CREATE INDEX idx_message_messages_room_id
    ON message_messages (room_id, id);

-- 3) users: (active, team_id) 복합 — 활성 사용자 + 팀 필터링이 매우 빈번
CREATE INDEX idx_users_active_team
    ON users (active, team_id);

-- 4) users: (active, system_account) — findEmployeePresence 등에서 시스템 계정 제외 필터
CREATE INDEX idx_users_active_system
    ON users (active, system_account);

-- 5) attendance_records: (work_date, user_id) — findEmployeePresence가 ar.work_date=? 조건으로 LEFT JOIN
CREATE INDEX idx_attendance_records_date_user
    ON attendance_records (work_date, user_id);

-- 6) leave_requests: status + (start_date,end_date) — findEmployeePresence의 EXISTS 서브쿼리 가속
CREATE INDEX idx_leave_requests_status_dates
    ON leave_requests (status, start_date, end_date);

-- 7) business_trips: status + (start_date,end_date) — 동일
CREATE INDEX idx_business_trips_status_dates
    ON business_trips (status, start_date, end_date);

-- 8) approval_steps: (document_type, document_id, status) — pending 단계 조회 빈번
CREATE INDEX idx_approval_steps_doc_status
    ON approval_steps (document_type, document_id, status);

-- 9) notifications: link_url prefix 인덱스 — 메시지 룸 알림 일괄 읽음 처리 시 LIKE 'messageRoomId=' 패턴 매칭 가속
--    MariaDB는 VARCHAR prefix index 지원. 64자 prefix면 'messageRoomId=12345' 같은 매칭에 충분
CREATE INDEX idx_notifications_link_url_prefix
    ON notifications (link_url(64));
