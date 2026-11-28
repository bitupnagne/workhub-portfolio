-- 인사평가 전면 개편 P1: 등급 마스터 + 직원 등급 지정 + 인사평가 관리자 역할
-- eval_group: CEO(대표이사)/EXEC(기타임원)=평가자 전용(피평가 제외), G12=1~2급, G34=3~4급, G57=5~7급.
-- 직책자(팀장/본부장) 평가그룹은 등급이 아니라 조직도(teams.leader_user_id)에서 도출한다.
CREATE TABLE emp_grades (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL,
  eval_group VARCHAR(10) NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_emp_grades_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO emp_grades(name, eval_group, display_order) VALUES
('대표이사', 'CEO',  1),
('기타임원', 'EXEC', 2),
('1급',     'G12',  3),
('2급',     'G12',  4),
('3급',     'G34',  5),
('4급',     'G34',  6),
('5급',     'G57',  7),
('6급',     'G57',  8),
('7급',     'G57',  9);

ALTER TABLE users
  ADD COLUMN grade_id BIGINT NULL AFTER job_title,
  ADD CONSTRAINT fk_users_grade FOREIGN KEY (grade_id) REFERENCES emp_grades(id);

INSERT INTO roles(code, name) VALUES ('EVAL_ADMIN', '인사평가 관리자');
