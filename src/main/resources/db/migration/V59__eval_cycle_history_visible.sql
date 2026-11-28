-- 인사평가: 회차별 이력 열람 토글.
-- OFF(기본): 평가 기간(마감 전)이 아니면 일반 사용자는 본인이 작성·제출한 평가도 열람 불가.
-- ON: 인사평가 관리자가 허용한 회차만 기간 외에도 열람 가능.
ALTER TABLE eval_cycles
  ADD COLUMN history_visible BOOLEAN NOT NULL DEFAULT FALSE AFTER final_ratio;
