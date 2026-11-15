-- [B6] 재직상태 3단계 — 휴직 기간 컬럼. active=TRUE+기간 내=휴직, active=FALSE=퇴직(기존 비활성 재해석).
ALTER TABLE users ADD COLUMN IF NOT EXISTS loa_start DATE NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS loa_end DATE NULL;
