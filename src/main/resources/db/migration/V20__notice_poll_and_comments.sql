-- 공지 투표 + 댓글 + 익명 허용. 진짜 익명: 익명 데이터에는 user_id를 저장하지 않고
-- 서버 비밀키 HMAC 토큰만 저장한다(비밀키는 DB가 아닌 앱 환경변수). DB만 봐선 식별 불가.

ALTER TABLE admin_notices
  ADD COLUMN allow_anonymous TINYINT(1) NOT NULL DEFAULT 0 AFTER body;

-- 공지당 투표 1개. 결과는 end_date 이후에만 공개.
CREATE TABLE notice_polls (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  notice_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_notice_poll (notice_id),
  CONSTRAINT fk_poll_notice FOREIGN KEY (notice_id) REFERENCES admin_notices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE poll_options (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  poll_id BIGINT NOT NULL,
  option_text VARCHAR(200) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_option_poll FOREIGN KEY (poll_id) REFERENCES notice_polls(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 익명 투표: 투표자 식별자(user_id)를 저장하지 않는다.
-- voter_token = HMAC_SHA256(서버비밀키, "poll:" + poll_id + ":" + user_id) 의 hex(64자).
-- UNIQUE(poll_id, voter_token) 로 1인 1표만 보장. 비밀키 없이는 토큰->사용자 역추적 불가.
CREATE TABLE poll_votes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  poll_id BIGINT NOT NULL,
  option_id BIGINT NOT NULL,
  voter_token CHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_poll_voter (poll_id, voter_token),
  CONSTRAINT fk_vote_poll FOREIGN KEY (poll_id) REFERENCES notice_polls(id) ON DELETE CASCADE,
  CONSTRAINT fk_vote_option FOREIGN KEY (option_id) REFERENCES poll_options(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 댓글. 익명 댓글은 author_user_id를 NULL로 두고 author_token(HMAC)만 저장 →
-- DB만 봐선 작성자 식별 불가. 일반 댓글은 author_user_id 사용.
CREATE TABLE notice_comments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  notice_id BIGINT NOT NULL,
  body VARCHAR(1000) NOT NULL,
  anonymous TINYINT(1) NOT NULL DEFAULT 0,
  author_user_id BIGINT NULL,
  author_token CHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  active TINYINT(1) NOT NULL DEFAULT 1,
  CONSTRAINT fk_comment_notice FOREIGN KEY (notice_id) REFERENCES admin_notices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_comment_notice ON notice_comments(notice_id, active);
