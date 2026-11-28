-- [A5] 연차 수동 조정 이력 — HR 담당자의 ± 조정을 사유·조정자와 함께 보존(본인 열람 가능).
CREATE TABLE IF NOT EXISTS leave_balance_adjustments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    balance_year INT NOT NULL,
    delta_days DECIMAL(5,2) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    adjusted_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_lba_user_year (user_id, balance_year)
);
