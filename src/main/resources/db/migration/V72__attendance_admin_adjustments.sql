-- [A3] HR 직권 근태 정정 감사 로그 — 관리자 매트릭스에서 직접 수정 시 전/후 값·사유·수정자 기록.
CREATE TABLE IF NOT EXISTS attendance_admin_adjustments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    work_date DATE NOT NULL,
    before_check_in_at DATETIME NULL,
    before_check_out_at DATETIME NULL,
    before_status VARCHAR(30) NULL,
    after_check_in_at DATETIME NULL,
    after_check_out_at DATETIME NULL,
    after_status VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    adjusted_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_att_adj_user_date (user_id, work_date)
);
