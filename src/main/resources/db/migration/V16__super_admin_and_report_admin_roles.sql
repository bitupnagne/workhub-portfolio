-- 슈퍼 관리자(개발자) 역할 추가
INSERT IGNORE INTO roles(code, name) VALUES ('SUPER_ADMIN', '슈퍼 관리자');

-- 개발자/슈퍼 관리자: 기본 시스템 관리자 계정(99)에 부여
INSERT IGNORE INTO user_roles(user_id, role_code) VALUES (99, 'SUPER_ADMIN');

-- 인가 모델을 팀 기반에서 개인 역할 기반으로 전환하므로,
-- 기존 주간보고 관리자 팀(app_settings.REPORT_ADMIN_TEAM_CODE, 기본 BIZ) 소속 활성 직원에게
-- REPORT_ADMIN 역할을 부여해 기존 권한을 보존한다.
INSERT IGNORE INTO user_roles(user_id, role_code)
SELECT u.id, 'REPORT_ADMIN'
FROM users u
JOIN teams t ON t.id = u.team_id
WHERE u.active = TRUE
  AND t.code = COALESCE(
      (SELECT setting_value FROM app_settings WHERE setting_key = 'REPORT_ADMIN_TEAM_CODE'),
      'BIZ');
