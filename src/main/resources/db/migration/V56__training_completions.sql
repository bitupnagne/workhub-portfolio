-- 인사평가 P6: 연수평가(3~7급) — 직능별 2과목(각 2점) + 선택 1과목(1점) = 최대 5점. 인사평가 관리자가 입력.
CREATE TABLE training_completions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  cycle_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  func_count INT NOT NULL DEFAULT 0,      -- 직능별 연수 이수 과목 수(0~2)
  elective_count INT NOT NULL DEFAULT 0,  -- 선택 연수 이수 과목 수(0~1)
  note VARCHAR(200) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_training (cycle_id, user_id),
  CONSTRAINT fk_training_cycle FOREIGN KEY (cycle_id) REFERENCES eval_cycles(id) ON DELETE CASCADE,
  CONSTRAINT fk_training_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
