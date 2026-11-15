-- =====================================================================
-- 오피스 시스템 — 주간보고 / 결재 데이터 리셋 스크립트
-- =====================================================================
--
-- 유지: 직원(users) / 조직(teams) / 권한(user_roles) / 결재경로 설정
--       (approval_routes / approval_route_rules / approval_route_receivers)
--       대리승인자(approval_delegations) / 근무시간 설정(attendance_settings,
--       attendance_day_overrides) / 회사 일정/휴일 / 공지 /
--       출퇴근 원본(attendance_records) / 서명 원본
--
-- 리셋: 주간보고, 결재 신청 본문(휴가/출장/시차/근태정정), 결재 진행/스냅샷,
--       휴가 누적 사용량, 메신저(방/멤버/메시지) 전체, 알림 전체
--
-- 사용법:
--   docker exec -i weekend-rocky810 mysql -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" < reset-approval-and-report.sql
--
-- 실행 전 백업 권장:
--   docker exec weekend-rocky810 mysqldump -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" > backup-$(date +%Y%m%d).sql
-- =====================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ----- 1) 주간보고 -----
TRUNCATE TABLE report_item_details;
TRUNCATE TABLE report_items;
TRUNCATE TABLE weekly_report_snapshots;
TRUNCATE TABLE report_audit_logs;
TRUNCATE TABLE weekly_reports;

-- ----- 2) 결재 진행 / 스냅샷 -----
TRUNCATE TABLE approval_steps;
TRUNCATE TABLE approval_document_receivers;
TRUNCATE TABLE approval_document_snapshots;

-- ----- 3) 결재 신청 본문 -----
TRUNCATE TABLE business_trip_receipts;
TRUNCATE TABLE business_trip_expenses;
TRUNCATE TABLE business_trips;
TRUNCATE TABLE attendance_corrections;
TRUNCATE TABLE flex_time_requests;
TRUNCATE TABLE leave_requests;

-- ----- 4) 휴가 누적 사용량 (잔여일수 정확성 유지) -----
TRUNCATE TABLE leave_balances;

-- ----- 5) 메신저 전체 (방 / 멤버 / 메시지) -----
TRUNCATE TABLE message_messages;
TRUNCATE TABLE message_room_members;
TRUNCATE TABLE message_rooms;

-- ----- 6) 알림 전체 -----
TRUNCATE TABLE notifications;

SET FOREIGN_KEY_CHECKS = 1;

-- ----- 7) 검증 -----
SELECT 'weekly_reports' AS tbl, COUNT(*) AS remaining FROM weekly_reports
UNION ALL SELECT 'report_items',       COUNT(*) FROM report_items
UNION ALL SELECT 'leave_requests',     COUNT(*) FROM leave_requests
UNION ALL SELECT 'business_trips',     COUNT(*) FROM business_trips
UNION ALL SELECT 'flex_time_requests', COUNT(*) FROM flex_time_requests
UNION ALL SELECT 'attendance_corrections', COUNT(*) FROM attendance_corrections
UNION ALL SELECT 'approval_steps',     COUNT(*) FROM approval_steps
UNION ALL SELECT 'approval_document_snapshots', COUNT(*) FROM approval_document_snapshots
UNION ALL SELECT 'leave_balances',     COUNT(*) FROM leave_balances
UNION ALL SELECT 'message_rooms',      COUNT(*) FROM message_rooms
UNION ALL SELECT 'message_room_members', COUNT(*) FROM message_room_members
UNION ALL SELECT 'message_messages',   COUNT(*) FROM message_messages
UNION ALL SELECT 'notifications',      COUNT(*) FROM notifications
UNION ALL SELECT 'users (preserved)',  COUNT(*) FROM users
UNION ALL SELECT 'teams (preserved)',  COUNT(*) FROM teams
UNION ALL SELECT 'approval_route_rules (preserved)', COUNT(*) FROM approval_route_rules;
