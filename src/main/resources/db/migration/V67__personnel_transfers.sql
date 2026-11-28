-- 인사이동 이력. 인사이동 기능으로 옮길 때만 기록(직원/조직관리 직접 팀변경은 기록 안 함).
-- 평가선 생성/집계에서 연도별로 참조(역량 일수 비례, 평가자 전·후 결정). 발령일 예약(미래)/과거/오늘 지원.
CREATE TABLE IF NOT EXISTS personnel_transfers (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    from_team_id    BIGINT NULL,
    to_team_id      BIGINT NOT NULL,
    effective_date  DATE NOT NULL,
    eval_year       INT NOT NULL,          -- effective_date 의 연도(연도별 이력관리)
    applied         BOOLEAN NOT NULL DEFAULT FALSE,  -- 팀 실제 반영 여부(미래 예약은 도래 시 스케줄러가 반영)
    applied_at      TIMESTAMP NULL,
    created_by      BIGINT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NULL,
    INDEX ix_pt_user (user_id),
    INDEX ix_pt_year (eval_year),
    INDEX ix_pt_due (applied, effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
