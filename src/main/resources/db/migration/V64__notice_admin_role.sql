-- 공지 관리 전용 역할 신설. 기존엔 조직 관리자/주간보고 관리자가 공지를 관리(권한 과다) → 전용 역할로 분리.
INSERT INTO roles(code, name) VALUES ('NOTICE_ADMIN', '공지 관리자')
ON DUPLICATE KEY UPDATE name = VALUES(name);
