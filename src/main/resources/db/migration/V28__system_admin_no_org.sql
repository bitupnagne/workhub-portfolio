-- 슈퍼관리자(시스템 계정)는 우측 직원목록에서 숨겨지므로 조직 소속을 두지 않는다.
-- 소속이 남아 있으면 조직도/결재선/카운트에서 혼선을 주므로 정리한다.

UPDATE users
SET team_id = NULL,
    dept = NULL,
    job_title = NULL
WHERE id = 99
  AND system_account = TRUE;
