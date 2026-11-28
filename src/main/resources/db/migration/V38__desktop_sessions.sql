CREATE TABLE desktop_device_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    device_name VARCHAR(120),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME NULL,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    UNIQUE KEY uk_desktop_device_tokens_hash (token_hash),
    KEY idx_desktop_device_tokens_user (user_id),
    CONSTRAINT fk_desktop_device_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE desktop_sso_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    redirect_path VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    used_at DATETIME NULL,
    UNIQUE KEY uk_desktop_sso_tokens_hash (token_hash),
    KEY idx_desktop_sso_tokens_user (user_id),
    CONSTRAINT fk_desktop_sso_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);
