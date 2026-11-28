-- [C2] 휴가 종류 정의 DB화 — 라벨/차감 가중치/연차 차감 여부/증빙 필요/사용 여부/정렬을 관리 가능하게.
-- code는 기존 LeaveType enum 이름과 1:1 (leave_requests.type 저장값과 호환 — 데이터 마이그레이션 불필요).
-- 반차 방향성(오전/오후 지각판정)은 enum 고유 시맨틱으로 코드에 남는다.
CREATE TABLE IF NOT EXISTS leave_types (
    code VARCHAR(30) PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    day_weight DECIMAL(2,1) NOT NULL DEFAULT 1.0,
    deduct_annual BOOLEAN NOT NULL DEFAULT FALSE,
    evidence_required BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0
);

INSERT IGNORE INTO leave_types(code, label, day_weight, deduct_annual, evidence_required, active, sort_order) VALUES
 ('ANNUAL',     '연차',          1.0, TRUE,  FALSE, TRUE, 1),
 ('AM_HALF',    '오전 반차',     0.5, TRUE,  FALSE, TRUE, 2),
 ('PM_HALF',    '오후 반차',     0.5, TRUE,  FALSE, TRUE, 3),
 ('SICK',       '병가',          1.0, FALSE, TRUE,  TRUE, 4),
 ('CONDOLENCE', '경조사',        1.0, FALSE, FALSE, TRUE, 5),
 ('OFFICIAL',   '공가',          1.0, FALSE, FALSE, TRUE, 6),
 ('HEALTH',     '보건휴가',      1.0, FALSE, FALSE, TRUE, 7),
 ('MATERNITY',  '출산휴가',      1.0, FALSE, FALSE, TRUE, 8),
 ('PARENTAL',   '육아휴직',      1.0, FALSE, FALSE, TRUE, 9),
 ('RESERVE',    '예비군/민방위', 1.0, FALSE, TRUE,  TRUE, 10),
 ('SPECIAL',    '특별휴가',      1.0, FALSE, FALSE, TRUE, 11);
