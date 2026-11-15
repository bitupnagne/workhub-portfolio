-- 취소·회수·반려는 신청 시 실제 차감한 일수만 되돌린다.
ALTER TABLE leave_requests
    ADD COLUMN annual_deducted_days DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER days;

-- 기존 신청에는 당시 설정 이력이 없다. 현재 종류 설정으로 초기화하며,
-- 과거에 차감 설정을 바꾼 사업장은 배포 전 원장과 대조해 보정해야 한다.
UPDATE leave_requests lr
JOIN leave_types lt ON lt.code = lr.type
SET lr.annual_deducted_days = CASE WHEN lt.deduct_annual THEN lr.days ELSE 0 END;
