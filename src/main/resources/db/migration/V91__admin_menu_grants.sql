-- [PERM] 관리자 메뉴별 권한 — 사람 또는 조직장 자리에 준다.
--
-- 왜: '인사 관리자' 같은 역할 하나가 근태 설정·연차·결재 경로·직원 정보를 통째로 가졌다. 급여를 다루기 시작하면
-- 근태 담당자가 전 직원 시급을 보게 된다. 메뉴 하나 = 권한 하나로 나눈다(AdminMenu).
--
-- grantee_type
--   USER        : 그 사람(user_id)
--   TEAM_LEADER : 그 조직의 조직장 자리(team_id) — 조직장이 바뀌면 권한도 따라간다(결재 경로의 '팀장' 지정과 같은 방식).
-- 슈퍼관리자는 행이 없어도 모든 메뉴를 가진다.
CREATE TABLE IF NOT EXISTS admin_menu_grants (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_key VARCHAR(40) NOT NULL,
  grantee_type VARCHAR(12) NOT NULL,
  user_id BIGINT NULL,
  team_id BIGINT NULL,
  grantee_key VARCHAR(40) AS (CONCAT(grantee_type, ':', COALESCE(user_id, team_id))) STORED,
  granted_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_admin_menu_grant (menu_key, grantee_key),
  KEY idx_admin_menu_grant_user (user_id),
  KEY idx_admin_menu_grant_team (team_id),
  CONSTRAINT fk_admin_menu_grant_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_admin_menu_grant_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 부여·회수 기록. 대상이 지워져도 남도록 이름을 같이 적는다.
CREATE TABLE IF NOT EXISTS admin_menu_grant_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_key VARCHAR(40) NOT NULL,
  action VARCHAR(10) NOT NULL,
  grantee_type VARCHAR(12) NOT NULL,
  user_id BIGINT NULL,
  team_id BIGINT NULL,
  grantee_label VARCHAR(200) NOT NULL,
  actor_id BIGINT NULL,
  actor_name VARCHAR(50) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_admin_menu_grant_logs_menu (menu_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 전환: 지금 역할을 가진 사람이 내일도 같은 메뉴를 쓸 수 있게, 역할에 맞는 메뉴 권한을 같은 사람에게 넣어 둔다.
-- 슈퍼관리자는 행이 필요 없다. 급여 관리는 새 메뉴라 아무에게도 미리 주지 않는다(슈퍼관리자가 직접 준다).
INSERT IGNORE INTO admin_menu_grants(menu_key, grantee_type, user_id)
SELECT m.menu_key, 'USER', u.id
FROM users u
JOIN user_roles ur ON ur.user_id = u.id
JOIN (
  SELECT 'ADMIN_PERMISSIONS' AS menu_key, 'SYSTEM_ADMIN' AS role_code
  UNION ALL SELECT 'ORG_CHART', 'SYSTEM_ADMIN' UNION ALL SELECT 'ORG_CHART', 'HR_ADMIN' UNION ALL SELECT 'ORG_CHART', 'ERP_ADMIN'
  UNION ALL SELECT 'USERS', 'SYSTEM_ADMIN' UNION ALL SELECT 'USERS', 'HR_ADMIN' UNION ALL SELECT 'USERS', 'ERP_ADMIN'
  UNION ALL SELECT 'APPROVAL_ROUTES', 'SYSTEM_ADMIN' UNION ALL SELECT 'APPROVAL_ROUTES', 'HR_ADMIN' UNION ALL SELECT 'APPROVAL_ROUTES', 'ERP_ADMIN'
  UNION ALL SELECT 'HR_DIAGNOSTICS', 'SYSTEM_ADMIN' UNION ALL SELECT 'HR_DIAGNOSTICS', 'HR_ADMIN' UNION ALL SELECT 'HR_DIAGNOSTICS', 'ERP_ADMIN'
  UNION ALL SELECT 'GRADES', 'SYSTEM_ADMIN' UNION ALL SELECT 'GRADES', 'HR_ADMIN' UNION ALL SELECT 'GRADES', 'EVAL_ADMIN'
  UNION ALL SELECT 'PERSONNEL_TRANSFER', 'SYSTEM_ADMIN' UNION ALL SELECT 'PERSONNEL_TRANSFER', 'HR_ADMIN' UNION ALL SELECT 'PERSONNEL_TRANSFER', 'EVAL_ADMIN'
  UNION ALL SELECT 'EVAL_KPI', 'SYSTEM_ADMIN' UNION ALL SELECT 'EVAL_KPI', 'HR_ADMIN' UNION ALL SELECT 'EVAL_KPI', 'EVAL_ADMIN'
  UNION ALL SELECT 'EVAL_TEMPLATES', 'SYSTEM_ADMIN' UNION ALL SELECT 'EVAL_TEMPLATES', 'HR_ADMIN' UNION ALL SELECT 'EVAL_TEMPLATES', 'EVAL_ADMIN'
  UNION ALL SELECT 'EVAL_CYCLES', 'SYSTEM_ADMIN' UNION ALL SELECT 'EVAL_CYCLES', 'HR_ADMIN' UNION ALL SELECT 'EVAL_CYCLES', 'EVAL_ADMIN'
  UNION ALL SELECT 'LEAVE_LEDGER', 'SYSTEM_ADMIN' UNION ALL SELECT 'LEAVE_LEDGER', 'HR_ADMIN'
  UNION ALL SELECT 'ATTENDANCE_POLICY', 'SYSTEM_ADMIN' UNION ALL SELECT 'ATTENDANCE_POLICY', 'HR_ADMIN'
  UNION ALL SELECT 'OVERTIME_SETTINGS', 'SYSTEM_ADMIN' UNION ALL SELECT 'OVERTIME_SETTINGS', 'HR_ADMIN'
  UNION ALL SELECT 'NOTICES', 'SYSTEM_ADMIN' UNION ALL SELECT 'NOTICES', 'NOTICE_ADMIN'
) m ON m.role_code = ur.role_code
WHERE u.active = 1
  AND NOT EXISTS (SELECT 1 FROM user_roles s WHERE s.user_id = u.id AND s.role_code = 'SUPER_ADMIN');

INSERT INTO admin_menu_grant_logs(menu_key, action, grantee_type, user_id, grantee_label, actor_name)
SELECT g.menu_key, 'GRANT', 'USER', g.user_id, u.name, '기존 역할에서 전환(V91)'
FROM admin_menu_grants g JOIN users u ON u.id = g.user_id;
