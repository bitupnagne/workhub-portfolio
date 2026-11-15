ALTER TABLE desktop_sso_tokens
    ADD COLUMN otp_verified BOOLEAN NOT NULL DEFAULT FALSE;
