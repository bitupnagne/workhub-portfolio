-- 초기/재설정 비밀번호 사용 중 여부 — 로그인 후 비밀번호 변경 모달 강제
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
