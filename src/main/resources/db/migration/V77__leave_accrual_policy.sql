-- [D3] 연차 부여 기준 정책 — HIRE_DATE(입사일 기준: 입사기념일마다 가산, 연중 증가) |
-- FISCAL_YEAR(회계연도 기준: 1/1 근속 1년 이상 직원은 연초 확정, 연중 불변).
-- effective_from_year 이전 연도는 기존(HIRE_DATE) 방식 유지. 시드=HIRE_DATE+2000 → 기존 동작 그대로.
CREATE TABLE IF NOT EXISTS leave_accrual_policy (
    id TINYINT PRIMARY KEY,
    basis VARCHAR(20) NOT NULL,
    effective_from_year INT NOT NULL,
    updated_by BIGINT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT IGNORE INTO leave_accrual_policy(id, basis, effective_from_year) VALUES (1, 'HIRE_DATE', 2000);
