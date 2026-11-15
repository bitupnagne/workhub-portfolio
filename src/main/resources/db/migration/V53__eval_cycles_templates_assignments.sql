-- 인사평가 전면 개편 P2: 평가 회차 + 양식/문항 마스터(새로 작성한 데모 질문 40종) + 평가선 + 평가위원
-- form_type: COMPETENCY(역량) / MULTI_UP(다면 상향) / MULTI_PEER(다면 팀장간)
--            / COOP_TEAM(부서간 업무협조도, 팀장용) / COOP_DIV(전사정책 이행협조도, 본부장·임원용)
-- target_group(역량만): LEADER(직책자) / G12 / G34 / G57
-- 역량 category_weight 합 = 그룹별 역량평가 최종 반영율(직책자 55 / 1~2급 60 / 3~4급 35 / 5~7급 35)

CREATE TABLE eval_cycles (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  eval_year INT NOT NULL,
  name VARCHAR(100) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  gen_log MEDIUMTEXT NULL,
  generated_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_cycle_year (eval_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE eval_templates (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  form_type VARCHAR(30) NOT NULL,
  target_group VARCHAR(10) NULL,
  name VARCHAR(100) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE eval_template_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_id BIGINT NOT NULL,
  item_code VARCHAR(20) NOT NULL,
  category VARCHAR(20) NULL,
  category_weight DECIMAL(5,2) NULL,
  item_score INT NOT NULL,
  question TEXT NOT NULL,
  display_order INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_eval_item_template FOREIGN KEY (template_id) REFERENCES eval_templates(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE eval_assignments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  cycle_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  target_user_id BIGINT NULL,
  evaluator_user_id BIGINT NOT NULL,
  evaluator_role VARCHAR(20) NOT NULL,   -- PRIMARY(1차 60%) / FINAL(최종 40%) / MULTI(다면) / COOP(협조도)
  evaluator_group VARCHAR(10) NULL,      -- 다면 집계용: LEADER(팀장군) / MEMBER(팀원군)
  weight DECIMAL(5,2) NULL,              -- PRIMARY 60 / FINAL 40, 다면·협조도 NULL(집계 시 군 가중)
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / IN_PROGRESS / SUBMITTED / EXPIRED
  extended_until DATETIME NULL,
  started_at DATETIME NULL,
  submitted_at DATETIME NULL,
  -- 제출 당시 조직도 스냅샷(피평가자/평가자)
  target_name_snapshot VARCHAR(50) NULL,
  target_team_snapshot VARCHAR(100) NULL,
  target_division_snapshot VARCHAR(100) NULL,
  target_job_snapshot VARCHAR(100) NULL,
  target_grade_snapshot VARCHAR(50) NULL,
  target_eval_group_snapshot VARCHAR(20) NULL,
  evaluator_name_snapshot VARCHAR(50) NULL,
  evaluator_team_snapshot VARCHAR(100) NULL,
  evaluator_job_snapshot VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_eval_asgn_cycle FOREIGN KEY (cycle_id) REFERENCES eval_cycles(id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_asgn_template FOREIGN KEY (template_id) REFERENCES eval_templates(id),
  CONSTRAINT fk_eval_asgn_target FOREIGN KEY (target_user_id) REFERENCES users(id),
  CONSTRAINT fk_eval_asgn_evaluator FOREIGN KEY (evaluator_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_eval_asgn_cycle_eval ON eval_assignments(cycle_id, evaluator_user_id, status);
CREATE INDEX idx_eval_asgn_cycle_target ON eval_assignments(cycle_id, target_user_id);

CREATE TABLE eval_committee_members (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  cycle_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role_label VARCHAR(50) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_eval_committee (cycle_id, user_id),
  CONSTRAINT fk_eval_cmte_cycle FOREIGN KEY (cycle_id) REFERENCES eval_cycles(id) ON DELETE CASCADE,
  CONSTRAINT fk_eval_cmte_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ───────────────── 가상 데모 양식 시드 ─────────────────
-- 역량평가서(직책자용): 공통5% + 직무20% + 리더십20% + 추진력10% = 55%, 문항당 5점
INSERT INTO eval_templates(form_type, target_group, name) VALUES ('COMPETENCY', 'LEADER', '데모 역량 점검 (리더)');
SET @t_leader = LAST_INSERT_ID();
INSERT INTO eval_templates(form_type, target_group, name) VALUES ('COMPETENCY', 'G12', '데모 역량 점검 (1~2급)');
SET @t_g12 = LAST_INSERT_ID();
INSERT INTO eval_templates(form_type, target_group, name) VALUES ('COMPETENCY', 'G34', '데모 역량 점검 (3~4급)');
SET @t_g34 = LAST_INSERT_ID();
INSERT INTO eval_templates(form_type, target_group, name) VALUES ('COMPETENCY', 'G57', '데모 역량 점검 (5~7급)');
SET @t_g57 = LAST_INSERT_ID();
INSERT INTO eval_templates(form_type, target_group, name) VALUES ('MULTI_UP', NULL, '데모 리더 피드백 (구성원)');
SET @t_up = LAST_INSERT_ID();
INSERT INTO eval_templates(form_type, target_group, name) VALUES ('MULTI_PEER', NULL, '데모 협업 피드백 (동료 리더)');
SET @t_peer = LAST_INSERT_ID();
INSERT INTO eval_templates(form_type, target_group, name) VALUES ('COOP_TEAM', NULL, '데모 팀 협업 점검');
INSERT INTO eval_templates(form_type, target_group, name) VALUES ('COOP_DIV', NULL, '데모 조직 협업 점검');

-- 공통역량 5문항 (직책자/1~2급/3~4급 @5점, 5~7급 @10점)
INSERT INTO eval_template_items(template_id, item_code, category, category_weight, item_score, question, display_order) VALUES
(@t_leader, 'C_01', 'COMMON', 5, 5, '공동 작업 보드에 자신의 진행 상황을 알아보기 쉽게 기록한다.', 1),
(@t_leader, 'C_02', 'COMMON', 5, 5, '완료한 작업의 근거 자료를 동료가 다시 확인할 수 있게 정리한다.', 2),
(@t_leader, 'C_03', 'COMMON', 5, 5, '동료의 작업에 도움이 되는 참고 자료와 사용 팁을 공유한다.', 3),
(@t_leader, 'C_04', 'COMMON', 5, 5, '함께 정한 협업 약속을 확인하고 개선할 점을 회의에서 제안한다.', 4),
(@t_leader, 'C_05', 'COMMON', 5, 5, '시연 사용자의 피드백을 분류하여 다음 개선 항목에 반영한다.', 5),
(@t_g12, 'C_01', 'COMMON', 5, 5, '공동 작업 보드에 자신의 진행 상황을 알아보기 쉽게 기록한다.', 1),
(@t_g12, 'C_02', 'COMMON', 5, 5, '완료한 작업의 근거 자료를 동료가 다시 확인할 수 있게 정리한다.', 2),
(@t_g12, 'C_03', 'COMMON', 5, 5, '동료의 작업에 도움이 되는 참고 자료와 사용 팁을 공유한다.', 3),
(@t_g12, 'C_04', 'COMMON', 5, 5, '함께 정한 협업 약속을 확인하고 개선할 점을 회의에서 제안한다.', 4),
(@t_g12, 'C_05', 'COMMON', 5, 5, '시연 사용자의 피드백을 분류하여 다음 개선 항목에 반영한다.', 5),
(@t_g34, 'C_01', 'COMMON', 5, 5, '공동 작업 보드에 자신의 진행 상황을 알아보기 쉽게 기록한다.', 1),
(@t_g34, 'C_02', 'COMMON', 5, 5, '완료한 작업의 근거 자료를 동료가 다시 확인할 수 있게 정리한다.', 2),
(@t_g34, 'C_03', 'COMMON', 5, 5, '동료의 작업에 도움이 되는 참고 자료와 사용 팁을 공유한다.', 3),
(@t_g34, 'C_04', 'COMMON', 5, 5, '함께 정한 협업 약속을 확인하고 개선할 점을 회의에서 제안한다.', 4),
(@t_g34, 'C_05', 'COMMON', 5, 5, '시연 사용자의 피드백을 분류하여 다음 개선 항목에 반영한다.', 5),
(@t_g57, 'C_01', 'COMMON', 10, 10, '공동 작업 보드에 자신의 진행 상황을 알아보기 쉽게 기록한다.', 1),
(@t_g57, 'C_02', 'COMMON', 10, 10, '완료한 작업의 근거 자료를 동료가 다시 확인할 수 있게 정리한다.', 2),
(@t_g57, 'C_03', 'COMMON', 10, 10, '동료의 작업에 도움이 되는 참고 자료와 사용 팁을 공유한다.', 3),
(@t_g57, 'C_04', 'COMMON', 10, 10, '함께 정한 협업 약속을 확인하고 개선할 점을 회의에서 제안한다.', 4),
(@t_g57, 'C_05', 'COMMON', 10, 10, '시연 사용자의 피드백을 분류하여 다음 개선 항목에 반영한다.', 5);

-- 직무역량 5문항 (직책자 20% / 1~2급 25% / 3~4급 15% @5점, 5~7급 25% @10점)
INSERT INTO eval_template_items(template_id, item_code, category, category_weight, item_score, question, display_order) VALUES
(@t_leader, 'J_01', 'JOB', 20, 5, '작업을 작은 단계로 나누고 각 단계의 완료 조건을 정리한다.', 11),
(@t_leader, 'J_02', 'JOB', 20, 5, '새로운 도구를 익힌 뒤 샘플 과제에 적용한 결과를 설명한다.', 12),
(@t_leader, 'J_03', 'JOB', 20, 5, '요구사항과 결과물을 비교하여 빠진 항목을 스스로 점검한다.', 13),
(@t_leader, 'J_04', 'JOB', 20, 5, '검토 회의에서 선택 가능한 대안과 각각의 장단점을 제시한다.', 14),
(@t_leader, 'J_05', 'JOB', 20, 5, '반복 작업을 줄일 수 있는 개선안을 실험하고 효과를 기록한다.', 15),
(@t_g12, 'J_01', 'JOB', 25, 5, '작업을 작은 단계로 나누고 각 단계의 완료 조건을 정리한다.', 11),
(@t_g12, 'J_02', 'JOB', 25, 5, '새로운 도구를 익힌 뒤 샘플 과제에 적용한 결과를 설명한다.', 12),
(@t_g12, 'J_03', 'JOB', 25, 5, '요구사항과 결과물을 비교하여 빠진 항목을 스스로 점검한다.', 13),
(@t_g12, 'J_04', 'JOB', 25, 5, '검토 회의에서 선택 가능한 대안과 각각의 장단점을 제시한다.', 14),
(@t_g12, 'J_05', 'JOB', 25, 5, '반복 작업을 줄일 수 있는 개선안을 실험하고 효과를 기록한다.', 15),
(@t_g34, 'J_01', 'JOB', 15, 5, '작업을 작은 단계로 나누고 각 단계의 완료 조건을 정리한다.', 11),
(@t_g34, 'J_02', 'JOB', 15, 5, '새로운 도구를 익힌 뒤 샘플 과제에 적용한 결과를 설명한다.', 12),
(@t_g34, 'J_03', 'JOB', 15, 5, '요구사항과 결과물을 비교하여 빠진 항목을 스스로 점검한다.', 13),
(@t_g34, 'J_04', 'JOB', 15, 5, '검토 회의에서 선택 가능한 대안과 각각의 장단점을 제시한다.', 14),
(@t_g34, 'J_05', 'JOB', 15, 5, '반복 작업을 줄일 수 있는 개선안을 실험하고 효과를 기록한다.', 15),
(@t_g57, 'J_01', 'JOB', 25, 10, '작업을 작은 단계로 나누고 각 단계의 완료 조건을 정리한다.', 11),
(@t_g57, 'J_02', 'JOB', 25, 10, '새로운 도구를 익힌 뒤 샘플 과제에 적용한 결과를 설명한다.', 12),
(@t_g57, 'J_03', 'JOB', 25, 10, '요구사항과 결과물을 비교하여 빠진 항목을 스스로 점검한다.', 13),
(@t_g57, 'J_04', 'JOB', 25, 10, '검토 회의에서 선택 가능한 대안과 각각의 장단점을 제시한다.', 14),
(@t_g57, 'J_05', 'JOB', 25, 10, '반복 작업을 줄일 수 있는 개선안을 실험하고 효과를 기록한다.', 15);

-- 리더십역량 5문항 (직책자 20% / 1~2급 15%, @5점. 3~7급 제외)
INSERT INTO eval_template_items(template_id, item_code, category, category_weight, item_score, question, display_order) VALUES
(@t_leader, 'L_01', 'LEADERSHIP', 20, 5, '팀의 다음 작업 목표와 우선순위를 짧은 실행 계획으로 정리한다.', 21),
(@t_leader, 'L_02', 'LEADERSHIP', 20, 5, '새 작업을 맡은 동료에게 예시 결과물과 참고 경로를 안내한다.', 22),
(@t_leader, 'L_03', 'LEADERSHIP', 20, 5, '구성원의 현재 작업량을 살펴 과제의 범위와 일정을 조율한다.', 23),
(@t_leader, 'L_04', 'LEADERSHIP', 20, 5, '서로 다른 제안을 비교할 수 있도록 공통 검토 기준을 마련한다.', 24),
(@t_leader, 'L_05', 'LEADERSHIP', 20, 5, '실험 결과를 팀과 돌아보고 다음 시도에서 바꿀 점을 정한다.', 25),
(@t_g12, 'L_01', 'LEADERSHIP', 15, 5, '팀의 다음 작업 목표와 우선순위를 짧은 실행 계획으로 정리한다.', 21),
(@t_g12, 'L_02', 'LEADERSHIP', 15, 5, '새 작업을 맡은 동료에게 예시 결과물과 참고 경로를 안내한다.', 22),
(@t_g12, 'L_03', 'LEADERSHIP', 15, 5, '구성원의 현재 작업량을 살펴 과제의 범위와 일정을 조율한다.', 23),
(@t_g12, 'L_04', 'LEADERSHIP', 15, 5, '서로 다른 제안을 비교할 수 있도록 공통 검토 기준을 마련한다.', 24),
(@t_g12, 'L_05', 'LEADERSHIP', 15, 5, '실험 결과를 팀과 돌아보고 다음 시도에서 바꿀 점을 정한다.', 25);

-- 추진력 5문항 (직책자 10% @5점 / 1~2급 15% @5점 / 3~4급 15% @10점. 5~7급 제외)
INSERT INTO eval_template_items(template_id, item_code, category, category_weight, item_score, question, display_order) VALUES
(@t_leader, 'E_01', 'DRIVE', 10, 5, '진행이 멈춘 과제에서 바로 시도할 수 있는 작은 행동을 찾는다.', 31),
(@t_leader, 'E_02', 'DRIVE', 10, 5, '발견한 문제의 재현 절차를 남기고 해결 후보를 우선순위로 정리한다.', 32),
(@t_leader, 'E_03', 'DRIVE', 10, 5, '연관된 작업의 의존 관계를 확인하여 필요한 협의를 먼저 준비한다.', 33),
(@t_leader, 'E_04', 'DRIVE', 10, 5, '일정 변경 가능성을 점검하고 대체 일정과 대응 방법을 제안한다.', 34),
(@t_leader, 'E_05', 'DRIVE', 10, 5, '공동 과제의 역할과 완료 기준을 관련 팀과 문서로 합의한다.', 35),
(@t_g12, 'E_01', 'DRIVE', 15, 5, '진행이 멈춘 과제에서 바로 시도할 수 있는 작은 행동을 찾는다.', 31),
(@t_g12, 'E_02', 'DRIVE', 15, 5, '발견한 문제의 재현 절차를 남기고 해결 후보를 우선순위로 정리한다.', 32),
(@t_g12, 'E_03', 'DRIVE', 15, 5, '연관된 작업의 의존 관계를 확인하여 필요한 협의를 먼저 준비한다.', 33),
(@t_g12, 'E_04', 'DRIVE', 15, 5, '일정 변경 가능성을 점검하고 대체 일정과 대응 방법을 제안한다.', 34),
(@t_g12, 'E_05', 'DRIVE', 15, 5, '공동 과제의 역할과 완료 기준을 관련 팀과 문서로 합의한다.', 35),
(@t_g34, 'E_01', 'DRIVE', 15, 10, '진행이 멈춘 과제에서 바로 시도할 수 있는 작은 행동을 찾는다.', 31),
(@t_g34, 'E_02', 'DRIVE', 15, 10, '발견한 문제의 재현 절차를 남기고 해결 후보를 우선순위로 정리한다.', 32),
(@t_g34, 'E_03', 'DRIVE', 15, 10, '연관된 작업의 의존 관계를 확인하여 필요한 협의를 먼저 준비한다.', 33),
(@t_g34, 'E_04', 'DRIVE', 15, 10, '일정 변경 가능성을 점검하고 대체 일정과 대응 방법을 제안한다.', 34),
(@t_g34, 'E_05', 'DRIVE', 15, 10, '공동 과제의 역할과 완료 기준을 관련 팀과 문서로 합의한다.', 35);

-- 다면평가서(상향) 10문항 @10점 (총 100점)
INSERT INTO eval_template_items(template_id, item_code, category, category_weight, item_score, question, display_order) VALUES
(@t_up, 'UP_01', NULL, NULL, 10, '팀의 실험이 막혔을 때 현재 상황을 함께 정리하는 시간을 마련한다.', 1),
(@t_up, 'UP_02', NULL, NULL, 10, '과제 배분의 기준을 구성원이 이해할 수 있도록 공개한다.', 2),
(@t_up, 'UP_03', NULL, NULL, 10, '정기 회의에서 작은 개선 결과도 공유할 수 있는 기회를 제공한다.', 3),
(@t_up, 'UP_04', NULL, NULL, 10, '제안받은 의견의 검토 결과와 후속 조치를 구성원에게 알려 준다.', 4),
(@t_up, 'UP_05', NULL, NULL, 10, '기존 작업 방식의 불편함을 확인하고 개선 실험을 지원한다.', 5),
(@t_up, 'UP_06', NULL, NULL, 10, '계획과 다른 결과가 나오면 배운 점과 재시도 방안을 함께 기록한다.', 6),
(@t_up, 'UP_07', NULL, NULL, 10, '도움이 필요한 구성원에게 적절한 참고 자료나 협업 상대를 연결한다.', 7),
(@t_up, 'UP_08', NULL, NULL, 10, '작업 요청에 기대 결과, 확인 시점, 필요한 자료를 포함한다.', 8),
(@t_up, 'UP_09', NULL, NULL, 10, '팀 간 변경 사항 중 구성원의 작업에 필요한 내용을 제때 전달한다.', 9),
(@t_up, 'UP_10', NULL, NULL, 10, '집중 작업 시간과 휴식 시간을 고려하여 회의 일정을 조율한다.', 10);

-- 다면평가서(팀장간) 10문항 @10점 (총 100점)
INSERT INTO eval_template_items(template_id, item_code, category, category_weight, item_score, question, display_order) VALUES
(@t_peer, 'PE_01', NULL, NULL, 10, '공동 검토에서 자신의 전문 분야를 이해하기 쉬운 예시로 설명한다.', 1),
(@t_peer, 'PE_02', NULL, NULL, 10, '여러 팀이 재사용할 수 있는 문서나 도구의 개선에 참여한다.', 2),
(@t_peer, 'PE_03', NULL, NULL, 10, '함께 맡은 과제의 약속 사항과 완료 결과를 끝까지 확인한다.', 3),
(@t_peer, 'PE_04', NULL, NULL, 10, '연계 기능의 변경 내용을 다른 팀이 준비할 수 있게 미리 안내한다.', 4),
(@t_peer, 'PE_05', NULL, NULL, 10, '팀 간 선택이 필요한 사안에서 판단 근거와 검토 조건을 공유한다.', 5),
(@t_peer, 'PE_06', NULL, NULL, 10, '협업 요청을 받으면 처리 범위와 가능한 일정을 먼저 확인한다.', 6),
(@t_peer, 'PE_07', NULL, NULL, 10, '공동 작업 규칙을 예시와 함께 설명하고 질문을 받을 시간을 마련한다.', 7),
(@t_peer, 'PE_08', NULL, NULL, 10, '검토 중 발견한 위험을 기록하고 해결 담당자와 후속 일정을 정한다.', 8),
(@t_peer, 'PE_09', NULL, NULL, 10, '다른 팀의 의견을 끝까지 확인한 뒤 사실과 제안을 구분하여 답한다.', 9),
(@t_peer, 'PE_10', NULL, NULL, 10, '팀 간 회고에서 효과가 있었던 협업 방법과 다음 개선점을 공유한다.', 10);
