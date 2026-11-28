-- [D3] 연차 부여일수 상한 — 근로기준법 제60조 4항은 가산휴가를 포함한 총 연차를 25일로 제한한다.
-- 지금까지 코드에는 상한이 없어서 15 + (근속-1)/2 가 그대로 나갔다(근속 23년이면 26일).
--
-- NULL = 상한 없음. 시드를 NULL 로 두는 이유는 기존 동작을 바꾸지 않기 위해서다 —
-- 상한을 둘지는 회사가 정할 일이고, 법정 최소를 웃도는 부여 자체는 위법이 아니다.
-- 관리자가 연차 원장 화면에서 값을 넣으면 그때부터 적용되고 원장도 재계산된다.
--
-- 상한은 <기본 부여일수(15일) 이상>만 저장되도록 앱에서 검증한다. 그래야 상한 때문에
-- 기본 부여가 깎이는 일이 생기지 않는다. 근속 1년 미만 월차(매월 +1, 최대 11)는
-- 법정상 별개로 발생하므로 이 상한을 적용하지 않는다.
SET @exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'leave_accrual_policy'
                 AND COLUMN_NAME = 'max_annual_days');
SET @sql = IF(@exists = 0,
              'ALTER TABLE leave_accrual_policy ADD COLUMN max_annual_days TINYINT NULL AFTER effective_from_year',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
