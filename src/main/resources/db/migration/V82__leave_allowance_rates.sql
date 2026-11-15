-- [D4] 연차수당 단가 — 미사용 연차는 이월하지 않고 수당으로 지급하며, 단가는 직급(등급)마다 다르다.
--
-- 연도를 키에 넣는 이유: 임금이 매년 오르므로 단가도 해마다 달라진다. 단가를 한 벌만 두면
-- 인상할 때 덮어써야 하고, 그러면 <이미 정산이 끝난 과거 연도>를 다시 뽑았을 때 금액이
-- 소급해서 바뀐다. 지급이 끝난 금액은 나중에 조회해도 그대로여야 한다.
--
-- 단가는 '연차 1일당 금액'을 관리자가 직접 넣는다(통상임금에서 계산하지 않는다) —
-- 회사가 정한 금액이 화면 값과 1:1로 맞아야 조정이 쉽다.
--
-- 행이 없으면 '단가 미설정'이다. 0 과 구분해야 한다: 0 은 "수당 없음"이라고 정한 것이고,
-- 행 없음은 "아직 안 정했다"라서 화면에서 경고해야 하는 상태다. 그래서 시드를 넣지 않는다.
CREATE TABLE IF NOT EXISTS leave_allowance_rates (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rate_year INT NOT NULL,
  grade_id BIGINT NOT NULL,
  -- 1일당 금액(원). 원 단위 미만은 쓰지 않는다.
  daily_amount DECIMAL(12,0) NOT NULL,
  updated_by BIGINT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_leave_allowance_year_grade (rate_year, grade_id),
  CONSTRAINT fk_leave_allowance_grade FOREIGN KEY (grade_id) REFERENCES emp_grades(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
