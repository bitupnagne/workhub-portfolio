-- 요일별 근무시간표 — 주 4.5일제 등 주간 근무 패턴 지원.
-- day_of_week: 1=월 ~ 7=일 (java.time.DayOfWeek.getValue())
-- work_type: FULL(전일) | HALF(반일) | OFF(휴무)
-- start/end NULL = 기본 근무시간(attendance_setting)을 따름. HALF에 시각이 없으면 출근 후 4시간.
CREATE TABLE IF NOT EXISTS attendance_week_schedule (
    day_of_week TINYINT PRIMARY KEY,
    work_type VARCHAR(10) NOT NULL DEFAULT 'FULL',
    start_time TIME NULL,
    end_time TIME NULL
);

-- 시드: 월~금 전일, 토·일 휴무 (기존 하드코딩 주말과 동일 동작)
INSERT INTO attendance_week_schedule(day_of_week, work_type)
SELECT d.dow, CASE WHEN d.dow >= 6 THEN 'OFF' ELSE 'FULL' END
FROM (SELECT 1 AS dow UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7) d
WHERE NOT EXISTS (SELECT 1 FROM attendance_week_schedule w WHERE w.day_of_week = d.dow);
