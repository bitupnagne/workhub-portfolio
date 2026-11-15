-- [UX47] 출장보고서 — 출장신청서와 세트. 결재 완료된 출장에 1건 작성, 별도 결재(TRIP_REPORT).
-- 경비는 신청 시점 테이블(business_trip_expenses)을 불변 보존하고, 보고 시점 복사본을 별도 저장해
-- '신청 당시 금액 vs 보고 금액' 비교를 문서에서 보여준다. 콜레이션은 기존 테이블과 동일하게 통일(V78 교훈).

CREATE TABLE business_trip_reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    expense_changed BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_approval_step INT NOT NULL DEFAULT 1,
    decided_by BIGINT NULL,
    rejected_reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uq_trip_report UNIQUE (trip_id),
    CONSTRAINT fk_trip_report_trip FOREIGN KEY (trip_id) REFERENCES business_trips(id)
) ENGINE=InnoDB;

CREATE TABLE business_trip_report_expenses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    expense_type VARCHAR(30) NOT NULL,
    description VARCHAR(200) NULL,
    amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    note VARCHAR(300) NULL,
    display_order INT NOT NULL DEFAULT 1,
    CONSTRAINT fk_trip_report_expense FOREIGN KEY (report_id) REFERENCES business_trip_reports(id)
) ENGINE=InnoDB;

CREATE TABLE business_trip_report_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trip_report_file FOREIGN KEY (report_id) REFERENCES business_trip_reports(id)
) ENGINE=InnoDB;

-- 출장보고 결재경로 기본값 = 출장(TRIP) 결재경로 복사 (이후 결재경로관리에서 자유 수정)
INSERT INTO approval_route_rules(document_type, scope_team_id, requester_level, step_order,
                                 approver_type, approver_user_id, approver_team_id, receiver_team_id, active)
SELECT 'TRIP_REPORT', scope_team_id, requester_level, step_order,
       approver_type, approver_user_id, approver_team_id, receiver_team_id, active
FROM approval_route_rules
WHERE document_type = 'TRIP';

-- 기존 테이블 콜레이션과 통일(V78과 동일 방식)
SET @coll = (SELECT COLLATION_NAME FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'business_trips' AND COLUMN_NAME = 'destination');
SET @sql = CONCAT('ALTER TABLE business_trip_reports CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = CONCAT('ALTER TABLE business_trip_report_expenses CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = CONCAT('ALTER TABLE business_trip_report_files CONVERT TO CHARACTER SET utf8mb4 COLLATE ', @coll);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
