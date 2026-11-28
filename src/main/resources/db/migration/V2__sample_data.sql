-- Portfolio demo: all people and organization labels below are synthetic.
INSERT INTO roles(code, name) VALUES
('SYSTEM_ADMIN','시스템 관리자'),
('REPORT_ADMIN','주간보고 관리자'),
('HR_ADMIN','HR 관리자'),
('ERP_ADMIN','ERP 관리자'),
('TEAM_LEADER','팀장'),
('EMPLOYEE','직원');

INSERT INTO teams(id, code, name, display_order) VALUES
(1,'FIN','운영지원팀',1),
(2,'STR','제품전략팀',2),
(3,'TRA','서비스기획팀',3),
(4,'BIZ','사업관리팀',4),
(5,'DEV','플랫폼개발팀',5),
(6,'SEC','보안관리팀',6),
(7,'OPS','서비스운영팀',7),
(8,'AUD','품질관리팀',8),
(9,'SUP','고객지원팀',9);

INSERT INTO app_settings(setting_key, setting_value) VALUES
('REPORT_ADMIN_TEAM_CODE','BIZ');

-- Fictional identities cannot authenticate until explicitly provisioned by the demo bootstrap.
INSERT INTO users(id, username, password, name, team_id, dept) VALUES
(99,'demo.admin','!DEMO_LOGIN_DISABLED','데모 관리자',4,'사업관리팀'),
(101,'demo.user101','!DEMO_LOGIN_DISABLED','데모 사용자 101',1,'운영지원팀장'),
(102,'demo.user102','!DEMO_LOGIN_DISABLED','데모 사용자 102',1,'운영지원파트'),
(103,'demo.user103','!DEMO_LOGIN_DISABLED','데모 사용자 103',1,'운영지원파트'),
(201,'demo.user201','!DEMO_LOGIN_DISABLED','데모 사용자 201',2,'제품전략팀장'),
(202,'demo.user202','!DEMO_LOGIN_DISABLED','데모 사용자 202',2,'제품전략파트'),
(203,'demo.user203','!DEMO_LOGIN_DISABLED','데모 사용자 203',2,'제품전략파트'),
(301,'demo.user301','!DEMO_LOGIN_DISABLED','데모 사용자 301',3,'서비스기획팀장'),
(302,'demo.user302','!DEMO_LOGIN_DISABLED','데모 사용자 302',3,'서비스기획파트'),
(303,'demo.user303','!DEMO_LOGIN_DISABLED','데모 사용자 303',3,'서비스기획파트'),
(401,'demo.user401','!DEMO_LOGIN_DISABLED','데모 사용자 401',4,'사업관리팀장'),
(402,'demo.user402','!DEMO_LOGIN_DISABLED','데모 사용자 402',4,'사업관리파트'),
(403,'demo.user403','!DEMO_LOGIN_DISABLED','데모 사용자 403',4,'사업관리파트'),
(501,'demo.user501','!DEMO_LOGIN_DISABLED','데모 사용자 501',5,'플랫폼개발팀장'),
(502,'demo.user502','!DEMO_LOGIN_DISABLED','데모 사용자 502',5,'플랫폼개발파트'),
(503,'demo.user503','!DEMO_LOGIN_DISABLED','데모 사용자 503',5,'플랫폼개발파트'),
(601,'demo.user601','!DEMO_LOGIN_DISABLED','데모 사용자 601',6,'보안관리팀장'),
(602,'demo.user602','!DEMO_LOGIN_DISABLED','데모 사용자 602',6,'보안관리파트'),
(603,'demo.user603','!DEMO_LOGIN_DISABLED','데모 사용자 603',6,'보안관리파트'),
(701,'demo.user701','!DEMO_LOGIN_DISABLED','데모 사용자 701',7,'서비스운영팀장'),
(702,'demo.user702','!DEMO_LOGIN_DISABLED','데모 사용자 702',7,'서비스운영파트'),
(703,'demo.user703','!DEMO_LOGIN_DISABLED','데모 사용자 703',7,'서비스운영파트'),
(801,'demo.user801','!DEMO_LOGIN_DISABLED','데모 사용자 801',8,'품질관리팀장'),
(802,'demo.user802','!DEMO_LOGIN_DISABLED','데모 사용자 802',8,'품질관리파트'),
(803,'demo.user803','!DEMO_LOGIN_DISABLED','데모 사용자 803',8,'품질관리파트'),
(901,'demo.user901','!DEMO_LOGIN_DISABLED','데모 사용자 901',9,'고객지원팀장'),
(902,'demo.user902','!DEMO_LOGIN_DISABLED','데모 사용자 902',9,'고객지원파트'),
(903,'demo.user903','!DEMO_LOGIN_DISABLED','데모 사용자 903',9,'고객지원파트');

INSERT INTO user_roles(user_id, role_code)
SELECT id, 'TEAM_LEADER' FROM users WHERE id IN (101,201,301,401,501,601,701,801,901);
INSERT INTO user_roles(user_id, role_code)
SELECT id, 'EMPLOYEE' FROM users WHERE id IN (102,103,202,203,302,303,402,403,502,503,602,603,702,703,802,803,902,903);
INSERT INTO user_roles(user_id, role_code) VALUES
(99,'SYSTEM_ADMIN'),(99,'HR_ADMIN'),(99,'ERP_ADMIN'),(99,'REPORT_ADMIN');

INSERT INTO weekly_confirmations(week_key, confirmed, confirmed_at, confirmed_by) VALUES
('2026-W12', TRUE, '2026-03-20 18:00:00', 99),
('2026-W13', TRUE, '2026-03-27 18:00:00', 99),
('2026-W14', FALSE, NULL, NULL);

INSERT INTO team_weekly_status(team_id, week_key, submitted, submitted_at) VALUES
(1,'2026-W14',TRUE,'2026-04-03 17:20:00'),
(2,'2026-W14',TRUE,'2026-04-03 17:25:00'),
(3,'2026-W14',TRUE,'2026-04-03 17:30:00'),
(4,'2026-W14',TRUE,'2026-04-03 17:35:00'),
(5,'2026-W14',TRUE,'2026-04-03 17:40:00'),
(6,'2026-W14',TRUE,'2026-04-03 17:45:00'),
(7,'2026-W14',TRUE,'2026-04-03 17:50:00'),
(8,'2026-W14',TRUE,'2026-04-03 17:55:00'),
(9,'2026-W14',FALSE,NULL);

INSERT INTO weekly_reports(id, user_id, week_key, submitted, submitted_at) VALUES
(1001,102,'2026-W14',TRUE,'2026-04-03 16:00:00'),
(1002,103,'2026-W14',TRUE,'2026-04-03 16:05:00'),
(1003,202,'2026-W14',TRUE,'2026-04-03 16:10:00'),
(1004,203,'2026-W14',TRUE,'2026-04-03 16:15:00'),
(1005,302,'2026-W14',TRUE,'2026-04-03 16:20:00'),
(1006,303,'2026-W14',TRUE,'2026-04-03 16:25:00'),
(1007,402,'2026-W14',TRUE,'2026-04-03 16:30:00'),
(1008,403,'2026-W14',TRUE,'2026-04-03 16:35:00'),
(1009,502,'2026-W14',TRUE,'2026-04-03 16:40:00'),
(1010,503,'2026-W14',TRUE,'2026-04-03 16:45:00'),
(1011,602,'2026-W14',TRUE,'2026-04-03 16:50:00'),
(1012,603,'2026-W14',TRUE,'2026-04-03 16:55:00'),
(1013,702,'2026-W14',TRUE,'2026-04-03 17:00:00'),
(1014,703,'2026-W14',TRUE,'2026-04-03 17:05:00'),
(1015,802,'2026-W14',TRUE,'2026-04-03 17:10:00'),
(1016,803,'2026-W14',TRUE,'2026-04-03 17:15:00'),
(1017,902,'2026-W14',TRUE,'2026-04-03 17:20:00'),
(1018,903,'2026-W14',FALSE,NULL);

INSERT INTO report_items(id, weekly_report_id, content, due_date, cycle, status, display_order) VALUES
(3001,1001,'부서별 예산 집계','2026-04-03','매주','NEW',1),
(3002,1001,'월간 재무제표 작성','2026-04-10','월1회','CONTINUE',2),
(3003,1002,'세금계산서 처리','2026-04-03','매주','CONTINUE',1),
(3004,1002,'2분기 결산 자료 검토','2026-04-15','1회','NEW',2),
(3005,1003,'사업 기획안 최종 검토','2026-04-07','발생시','CONTINUE',1),
(3006,1003,'신규 파트너사 발굴','2026-04-30','1회','NEW',2),
(3007,1004,'파트너사 계약 체결','2026-04-10','발생시','CONTINUE',1),
(3012,1007,'주간 KPI 집계','2026-04-03','매주','CONTINUE',1),
(3013,1007,'임원보고 자료 준비','2026-04-07','발생시','NEW',2),
(3014,1008,'부서별 성과 취합','2026-04-03','매주','CONTINUE',1),
(3015,1008,'분기 성과 보고서','2026-04-15','분기1회','NEW',2),
(3016,1009,'신규 업무 API 개발','2026-04-03','매주','NEW',1),
(3017,1009,'코드 리뷰 진행','2026-04-02','매주','CONTINUE',2),
(3018,1010,'프론트엔드 UI 개선','2026-04-10','발생시','NEW',1),
(3019,1010,'단위 테스트 작성','2026-03-31','매주','DONE',2),
(3020,1011,'보안 취약점 점검','2026-04-03','매주','CONTINUE',1),
(3021,1011,'접근권한 재검토','2026-04-13','분기1회','NEW',2),
(3022,1012,'보안 패치 적용','2026-04-10','발생시','NEW',1),
(3023,1012,'정보보호 교육 자료 준비','2026-04-07','발생시','DONE',2),
(3024,1013,'월간 정산 처리','2026-04-10','월1회','CONTINUE',1),
(3025,1013,'정산 오류 건 검토','2026-04-03','매주','NEW',2),
(3026,1014,'정산 시스템 점검','2026-04-08','매주','CONTINUE',1),
(3027,1014,'거래처 대사 작업','2026-04-03','매주','DONE',2),
(3028,1015,'내부 감사 2차 실시','2026-04-15','분기1회','CONTINUE',1),
(3029,1015,'감사 결과 보고서 작성','2026-04-20','발생시','NEW',2),
(3030,1016,'규정 준수 현황 점검','2026-04-10','매주','CONTINUE',1),
(3031,1016,'리스크 평가 보고','2026-04-07','반기1회','DONE',2),
(3032,1017,'사업 현황 집계','2026-04-03','매주','CONTINUE',1),
(3033,1017,'신규 파트너사 발굴','2026-04-20','1회','NEW',2),
(3034,1018,'지원 업무 처리','2026-04-03','매주','CONTINUE',1);

INSERT INTO report_item_details(id, report_item_id, text, due_date, cycle, display_order) VALUES
(9020,3002,'수입 항목 초안 작성','2026-04-02','1회',1),
(9021,3002,'지출 항목 검토','2026-04-03','1회',2),
(9030,3016,'업무모듈 인터페이스 설계','2026-04-01','1회',1),
(9031,3016,'단위 테스트 작성','2026-04-03','매주',2);
