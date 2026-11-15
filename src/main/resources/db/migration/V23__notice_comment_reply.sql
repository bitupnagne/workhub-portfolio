-- 공지 댓글 답글(1계층). parent_id가 NULL이면 최상위 댓글, 값이 있으면 답글.
ALTER TABLE notice_comments
  ADD COLUMN parent_id BIGINT NULL AFTER notice_id,
  ADD CONSTRAINT fk_comment_parent FOREIGN KEY (parent_id)
      REFERENCES notice_comments(id) ON DELETE CASCADE;
