-- 투표 시작/종료를 일자에서 일시(시·분 포함)로 확장.
ALTER TABLE notice_polls
  MODIFY COLUMN start_date DATETIME NOT NULL,
  MODIFY COLUMN end_date DATETIME NOT NULL;
