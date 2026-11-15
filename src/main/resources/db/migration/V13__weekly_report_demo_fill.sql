INSERT INTO team_weekly_status(team_id, week_key, submitted, submitted_at)
SELECT t.id, w.week_key, TRUE, DATE_ADD(CONCAT(w.friday, ' 17:00:00'), INTERVAL MOD(t.display_order * 3, 50) MINUTE)
FROM teams t
JOIN (
    SELECT '2026-W20' AS week_key, DATE('2026-05-15') AS friday
    UNION ALL SELECT '2026-W21', DATE('2026-05-22')
    UNION ALL SELECT '2026-W22', DATE('2026-05-29')
) w
WHERE t.active = TRUE
ON DUPLICATE KEY UPDATE
    submitted = VALUES(submitted),
    submitted_at = COALESCE(team_weekly_status.submitted_at, VALUES(submitted_at));

INSERT INTO weekly_reports(user_id, week_key, submitted, submitted_at)
SELECT u.id, w.week_key, TRUE, CONCAT(w.friday, ' 16:', LPAD(MOD(u.id, 50), 2, '0'), ':00')
FROM users u
JOIN user_roles ur ON ur.user_id = u.id AND ur.role_code = 'EMPLOYEE'
JOIN (
    SELECT '2026-W20' AS week_key, DATE('2026-05-15') AS friday
    UNION ALL SELECT '2026-W21', DATE('2026-05-22')
    UNION ALL SELECT '2026-W22', DATE('2026-05-29')
) w
WHERE u.active = TRUE
ON DUPLICATE KEY UPDATE
    submitted = VALUES(submitted),
    submitted_at = COALESCE(weekly_reports.submitted_at, VALUES(submitted_at));

INSERT INTO report_items(weekly_report_id, content, due_date, cycle, status, display_order)
SELECT demo.weekly_report_id, demo.content, demo.due_date, demo.cycle, demo.status, demo.display_order
FROM (
    SELECT
        wr.id AS weekly_report_id,
        CASE s.slot
            WHEN 1 THEN CASE t.code
                WHEN 'FIN' THEN CONCAT(w.label, ' 예산 집행 현황 점검')
                WHEN 'STR' THEN CONCAT(w.label, ' 신규 사업 전략 검토')
                WHEN 'TRA' THEN CONCAT(w.label, ' 서비스 이용 데이터 분석')
                WHEN 'BIZ' THEN CONCAT(w.label, ' 경영 KPI 취합')
                WHEN 'DEV' THEN CONCAT(w.label, ' API 안정화 및 배포 점검')
                WHEN 'SEC' THEN CONCAT(w.label, ' 보안 취약점 점검')
                WHEN 'OPS' THEN CONCAT(w.label, ' 정산 오류 모니터링')
                WHEN 'AUD' THEN CONCAT(w.label, ' 내부 통제 점검')
                ELSE CONCAT(w.label, ' 파트너 지원 현황 점검')
            END
            WHEN 2 THEN CASE t.code
                WHEN 'FIN' THEN CONCAT(w.label, ' 월간 손익 자료 작성')
                WHEN 'STR' THEN CONCAT(w.label, ' 시장 동향 리서치')
                WHEN 'TRA' THEN CONCAT(w.label, ' 서비스 운영 계획 개선안 작성')
                WHEN 'BIZ' THEN CONCAT(w.label, ' 임원 보고 자료 준비')
                WHEN 'DEV' THEN CONCAT(w.label, ' 프론트엔드 UX 개선')
                WHEN 'SEC' THEN CONCAT(w.label, ' 접근 권한 재검토')
                WHEN 'OPS' THEN CONCAT(w.label, ' 거래처 대사 작업')
                WHEN 'AUD' THEN CONCAT(w.label, ' 감사 결과 보고서 작성')
                ELSE CONCAT(w.label, ' 고객사 요청 처리')
            END
            ELSE CASE t.code
                WHEN 'FIN' THEN CONCAT(w.label, ' 세금계산서 처리 완료')
                WHEN 'STR' THEN CONCAT(w.label, ' 제안서 초안 검토 완료')
                WHEN 'TRA' THEN CONCAT(w.label, ' 서비스 현황 집계 완료')
                WHEN 'BIZ' THEN CONCAT(w.label, ' 부서별 성과 취합 완료')
                WHEN 'DEV' THEN CONCAT(w.label, ' 코드 리뷰 및 단위 테스트 완료')
                WHEN 'SEC' THEN CONCAT(w.label, ' 보안 패치 적용 완료')
                WHEN 'OPS' THEN CONCAT(w.label, ' 정산 시스템 점검 완료')
                WHEN 'AUD' THEN CONCAT(w.label, ' 규정 준수 점검 완료')
                ELSE CONCAT(w.label, ' 지원 요청 처리 완료')
            END
        END AS content,
        CASE s.slot
            WHEN 1 THEN w.friday
            WHEN 2 THEN DATE_ADD(w.friday, INTERVAL 4 DAY)
            ELSE DATE_SUB(w.friday, INTERVAL 1 DAY)
        END AS due_date,
        CASE s.slot WHEN 1 THEN '매주' WHEN 2 THEN '발생시' ELSE '1회' END AS cycle,
        CASE s.slot WHEN 1 THEN 'CONTINUE' WHEN 2 THEN 'NEW' ELSE 'DONE' END AS status,
        s.slot AS display_order
    FROM weekly_reports wr
    JOIN users u ON u.id = wr.user_id
    JOIN teams t ON t.id = u.team_id
    JOIN user_roles ur ON ur.user_id = u.id AND ur.role_code = 'EMPLOYEE'
    JOIN (
        SELECT '2026-W20' AS week_key, '2026년 20주차' AS label, DATE('2026-05-15') AS friday
        UNION ALL SELECT '2026-W21', '2026년 21주차', DATE('2026-05-22')
        UNION ALL SELECT '2026-W22', '2026년 22주차', DATE('2026-05-29')
    ) w ON w.week_key = wr.week_key
    JOIN (
        SELECT 1 AS slot
        UNION ALL SELECT 2
        UNION ALL SELECT 3
    ) s
    WHERE u.active = TRUE
) demo
WHERE NOT EXISTS (
    SELECT 1
    FROM report_items ri
    WHERE ri.weekly_report_id = demo.weekly_report_id
      AND ri.content = demo.content
);

INSERT INTO report_item_details(report_item_id, text, due_date, cycle, display_order)
SELECT ri.id,
       CASE d.seq
           WHEN 1 THEN '담당 자료 취합 및 현황 확인'
           ELSE '리스크 항목 정리 및 후속 일정 공유'
       END AS text,
       CASE d.seq
           WHEN 1 THEN DATE_SUB(ri.due_date, INTERVAL 1 DAY)
           ELSE ri.due_date
       END AS due_date,
       CASE d.seq WHEN 1 THEN '매주' ELSE '1회' END AS cycle,
       d.seq AS display_order
FROM report_items ri
JOIN weekly_reports wr ON wr.id = ri.weekly_report_id
JOIN (
    SELECT '2026-W20' AS week_key
    UNION ALL SELECT '2026-W21'
    UNION ALL SELECT '2026-W22'
) w ON w.week_key = wr.week_key
JOIN (
    SELECT 1 AS seq
    UNION ALL SELECT 2
) d
WHERE ri.status IN ('NEW', 'CONTINUE')
  AND ri.content LIKE '2026년 %주차%'
  AND NOT EXISTS (
      SELECT 1
      FROM report_item_details rid
      WHERE rid.report_item_id = ri.id
        AND rid.display_order = d.seq
  );
