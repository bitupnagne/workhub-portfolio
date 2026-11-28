-- 평가회차 감사 컬럼: 생성자(created_by)·최종 수정자(updated_by)·수정시각(updated_at).
-- 글쓴이/수정한 사람 개념. 회차 생성·정보수정·시작/마감/재오픈·이력토글·평가선 생성·결과 확정 시 갱신.
-- 기존 행은 NULL(표시 시 '-'). 멱등 위해 IF NOT EXISTS.
ALTER TABLE eval_cycles ADD COLUMN IF NOT EXISTS created_by BIGINT NULL;
ALTER TABLE eval_cycles ADD COLUMN IF NOT EXISTS updated_by BIGINT NULL;
ALTER TABLE eval_cycles ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NULL DEFAULT NULL;
