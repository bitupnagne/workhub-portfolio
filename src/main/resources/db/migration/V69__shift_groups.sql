-- 시차출근 조(ShiftGroup) DB화 — enum 하드코딩(A/B/C) → 관리 가능한 테이블.
-- flex_time_requests.shift_group(VARCHAR)은 그대로 code를 저장하므로 기존 데이터 호환.
CREATE TABLE IF NOT EXISTS shift_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE,
    label VARCHAR(100) NOT NULL,
    start_time TIME NULL,
    end_time TIME NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0
);

-- 기존 enum 값 시드 (start/end NULL = 기본 근무시간 따름)
INSERT INTO shift_groups(code, label, start_time, end_time, active, sort_order)
SELECT 'A', 'A조 (08:00~17:00)', '08:00:00', '17:00:00', TRUE, 1
WHERE NOT EXISTS (SELECT 1 FROM shift_groups WHERE code = 'A');
INSERT INTO shift_groups(code, label, start_time, end_time, active, sort_order)
SELECT 'B', 'B조 (기본 근무시간)', NULL, NULL, TRUE, 2
WHERE NOT EXISTS (SELECT 1 FROM shift_groups WHERE code = 'B');
INSERT INTO shift_groups(code, label, start_time, end_time, active, sort_order)
SELECT 'C', 'C조 (10:00~18:00)', '10:00:00', '18:00:00', TRUE, 3
WHERE NOT EXISTS (SELECT 1 FROM shift_groups WHERE code = 'C');
