-- 인사평가: 회차 기간 유형(연간/상반기/하반기) + 평가선 피평가 제외 명단
-- period_type: YEAR(연간) / H1(상반기) / H2(하반기). 같은 연도에 연간 ↔ 상·하반기는 상호 배타(서비스 검증).
ALTER TABLE eval_cycles
  ADD COLUMN period_type VARCHAR(10) NOT NULL DEFAULT 'YEAR' AFTER eval_year,
  DROP INDEX uk_eval_cycle_year,
  ADD UNIQUE KEY uk_eval_cycle_year_type (eval_year, period_type);

-- 평가선 생성 시 '피평가 대상'에서만 제외(평가자 역할은 유지). 조직 단위 제외는 개인으로 펼쳐 저장.
CREATE TABLE eval_cycle_exclusions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  cycle_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_excl (cycle_id, user_id),
  CONSTRAINT fk_eval_excl_cycle FOREIGN KEY (cycle_id) REFERENCES eval_cycles(id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_excl_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
