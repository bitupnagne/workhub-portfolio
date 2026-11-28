-- 조정계수 전체평균 G 수동 지정(토글 + 수동값). 토글 ON이면 자동 계산 G 대신 수동값 사용.
ALTER TABLE eval_cycles
    ADD COLUMN comp_g_manual BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN comp_g_value DECIMAL(6,2) NULL;
