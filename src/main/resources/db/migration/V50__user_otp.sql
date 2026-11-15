-- 관리자 2차 인증(TOTP) 컬럼. opt-in: 기본 비활성.
ALTER TABLE users
    ADD COLUMN otp_secret VARCHAR(64) NULL,
    ADD COLUMN otp_enabled TINYINT(1) NOT NULL DEFAULT 0;
