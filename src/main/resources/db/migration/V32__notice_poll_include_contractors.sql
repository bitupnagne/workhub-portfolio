ALTER TABLE notice_polls
  ADD COLUMN include_contractors TINYINT(1) NOT NULL DEFAULT 1 AFTER end_date;
