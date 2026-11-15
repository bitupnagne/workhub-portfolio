-- 휴가 취소 결재 사유 (원 신청 사유(reason)와 별도 보존)
ALTER TABLE leave_requests
    ADD COLUMN cancel_reason VARCHAR(500) NULL AFTER rejected_reason;
