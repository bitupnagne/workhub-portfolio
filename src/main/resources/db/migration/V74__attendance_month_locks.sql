-- [A4] 근태 월 마감 — 마감/해제 이벤트 이력(감사 로그 겸용). 최신 이벤트가 LOCK이면 마감 상태.
CREATE TABLE IF NOT EXISTS attendance_month_lock_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ym CHAR(7) NOT NULL,
    action VARCHAR(10) NOT NULL,
    acted_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_amle_ym (ym, id)
);
