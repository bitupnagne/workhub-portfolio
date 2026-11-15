-- 업무전달 (장기 보관·검색용 메시지) 기반 테이블
-- 채팅과 별개: 채팅은 실시간/단기, 업무전달은 보관/검색 중심

CREATE TABLE IF NOT EXISTS work_memos (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sender_user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    body MEDIUMTEXT,
    importance VARCHAR(16) NOT NULL DEFAULT 'NORMAL', -- NORMAL or IMPORTANT
    sent_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_work_memos_sender (sender_user_id, sent_at DESC),
    INDEX idx_work_memos_sent_at (sent_at DESC)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS work_memo_recipients (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    memo_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    read_at DATETIME NULL,
    is_starred BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_work_memo_recipient (memo_id, recipient_user_id),
    INDEX idx_recipient_inbox (recipient_user_id, read_at, memo_id DESC),
    INDEX idx_recipient_starred (recipient_user_id, is_starred, memo_id DESC),
    CONSTRAINT fk_work_memo_recipient_memo FOREIGN KEY (memo_id) REFERENCES work_memos(id) ON DELETE CASCADE
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS work_memo_attachments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    memo_id BIGINT NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    stored_filename VARCHAR(500) NOT NULL,
    content_type VARCHAR(150),
    file_size BIGINT NOT NULL DEFAULT 0,
    attachment_type VARCHAR(16) NOT NULL DEFAULT 'FILE', -- IMAGE or FILE
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_work_memo_attachments_memo (memo_id),
    CONSTRAINT fk_work_memo_attachment_memo FOREIGN KEY (memo_id) REFERENCES work_memos(id) ON DELETE CASCADE
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
