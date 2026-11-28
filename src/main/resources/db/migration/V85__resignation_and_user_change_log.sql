-- [B8] 퇴사자 중도 정산 + [B9] 인사정보 변경이력.

-- ── [B8] 퇴사일 ─────────────────────────────────────────────
-- 지금까지 퇴직은 active=FALSE 하나로만 표현됐다. <언제> 그만뒀는지가 없으니
-- 그 해 연차를 재직 기간에 비례해 정산할 수가 없었다 — 6월에 그만둔 사람도
-- 연말 기준 부여일수를 그대로 가진 채 남았고, 그게 연차수당 정산에 그대로 들어갔다.
--
-- active 와 분리해 둔다. 퇴사일이 있어도 계정을 바로 잠그지 않는 운영(인수인계 기간)이
-- 있을 수 있고, 반대로 퇴사일 없이 비활성만 된 옛 데이터도 그대로 살아 있어야 한다.
SET @has_resigned = (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'resigned_on');
SET @sql = IF(@has_resigned = 0,
              'ALTER TABLE users ADD COLUMN resigned_on DATE NULL AFTER hire_date',
              'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── [B9] 인사정보 변경이력 ───────────────────────────────────
-- 인사카드에 "누가 언제 무엇을 무엇으로 바꿨는지"가 남아야 한다. 지금은 인사이동
-- (personnel_transfers)만 기록되고, 그마저도 인사이동 기능으로 옮길 때만 남는다 —
-- 조직관리에서 팀을 직접 바꾸거나, 직급을 바꾸거나, 본인이 마이페이지에서 연락처를
-- 고치면 아무 흔적이 없다.
--
-- 필드 단위로 한 줄씩 남긴다. 한 번의 저장에서 세 개가 바뀌면 세 줄이 되고, 같은
-- changed_at 으로 묶여 화면에서 한 묶음으로 보인다.
--
-- old_value/new_value 를 <표시용 문자열>로 저장하는 이유: 팀 id 3 보다 '플랫폼개발팀'이
-- 이력에서 읽히고, 나중에 그 팀이 사라지거나 이름이 바뀌어도 <그때 무엇이었는지>가
-- 그대로 남는다. 이력은 지금이 아니라 그때를 말해야 한다.
CREATE TABLE IF NOT EXISTS user_change_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  -- 바뀐 항목 코드(name/teamId/gradeId/... 또는 LOA/RESIGN 같은 사건)
  field VARCHAR(40) NOT NULL,
  -- 화면에 보일 항목 이름('소속','직급','휴직' 등) — 코드가 바뀌어도 이력은 그대로 읽힌다.
  field_label VARCHAR(60) NOT NULL,
  old_value VARCHAR(255) NULL,
  new_value VARCHAR(255) NULL,
  -- 어느 경로로 바뀌었는지: SELF(본인) / ADMIN(관리자 직원관리) / HR_CARD(인사카드)
  --                        / TRANSFER(인사이동) / LOA(휴직 결재·처리) / SYSTEM(스케줄러 등)
  source VARCHAR(20) NOT NULL,
  changed_by BIGINT NULL,
  changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY ix_user_change_logs_user (user_id, changed_at),
  CONSTRAINT fk_user_change_logs_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
