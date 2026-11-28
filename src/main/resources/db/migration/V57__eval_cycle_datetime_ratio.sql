-- 인사평가 UX 개편: 실시 기간 분 단위 + 1차/최종 평가 비율 커스텀(기본 60/40)
ALTER TABLE eval_cycles
  MODIFY start_date DATETIME NOT NULL,
  MODIFY end_date DATETIME NOT NULL,
  ADD COLUMN primary_ratio DECIMAL(5,2) NOT NULL DEFAULT 60 AFTER end_date,
  ADD COLUMN final_ratio DECIMAL(5,2) NOT NULL DEFAULT 40 AFTER primary_ratio;

-- 기존 DATE 값은 00:00:00 으로 변환되므로 종료일을 그날 23:59:59 로 보정
UPDATE eval_cycles SET end_date = DATE_ADD(DATE(end_date), INTERVAL '23:59:59' HOUR_SECOND);
