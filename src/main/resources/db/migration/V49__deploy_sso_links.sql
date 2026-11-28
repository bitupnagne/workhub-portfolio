CREATE TABLE IF NOT EXISTS deploy_sso_links (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    office_user_id BIGINT NOT NULL,
    office_username VARCHAR(100) NOT NULL,
    deploy_user_id BIGINT NOT NULL,
    deploy_login_id VARCHAR(100) NOT NULL,
    deploy_name VARCHAR(100) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    linked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_deploy_sso_links_office_user (office_user_id),
    KEY idx_deploy_sso_links_username (office_username),
    CONSTRAINT fk_deploy_sso_links_office_user FOREIGN KEY (office_user_id) REFERENCES users(id) ON DELETE CASCADE
);
