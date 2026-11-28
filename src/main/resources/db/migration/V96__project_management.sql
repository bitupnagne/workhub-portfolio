CREATE TABLE projects (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    owner_id BIGINT NOT NULL,
    start_date DATE NULL,
    due_date DATE NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_project_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE','COMPLETED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_members (
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT fk_project_member_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_member_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX ix_project_member_user (user_id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description MEDIUMTEXT NOT NULL,
    assignee_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'TODO',
    progress INT NOT NULL DEFAULT 0,
    due_date DATE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_project_task_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_task_assignee FOREIGN KEY (assignee_id) REFERENCES users(id),
    CONSTRAINT fk_project_task_creator FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT ck_project_task_status CHECK (status IN ('TODO','IN_PROGRESS','REVIEW','BLOCKED','DONE')),
    CONSTRAINT ck_project_task_progress CHECK (progress BETWEEN 0 AND 100),
    INDEX ix_project_tasks_project (project_id, status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_task_collaborators (
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (task_id, user_id),
    CONSTRAINT fk_project_collaborator_task FOREIGN KEY (task_id) REFERENCES project_tasks(id),
    CONSTRAINT fk_project_collaborator_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_task_checklist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    text VARCHAR(500) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_project_checklist_task FOREIGN KEY (task_id) REFERENCES project_tasks(id),
    INDEX ix_project_checklist_task (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_task_comments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_project_comment_task FOREIGN KEY (task_id) REFERENCES project_tasks(id),
    CONSTRAINT fk_project_comment_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX ix_project_comment_task (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_task_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    kind VARCHAR(10) NOT NULL,
    title VARCHAR(200) NOT NULL,
    url VARCHAR(2000) NULL,
    original_filename VARCHAR(255) NULL,
    stored_filename VARCHAR(100) NULL,
    content_type VARCHAR(200) NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    uploaded_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_project_evidence_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_evidence_task FOREIGN KEY (task_id) REFERENCES project_tasks(id),
    CONSTRAINT fk_project_evidence_user FOREIGN KEY (uploaded_by) REFERENCES users(id),
    CONSTRAINT ck_project_evidence_kind CHECK (kind IN ('LINK','FILE')),
    INDEX ix_project_evidence_task (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_task_handovers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    from_user_id BIGINT NOT NULL,
    to_user_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response TEXT NULL,
    requested_at DATETIME(6) NOT NULL,
    responded_at DATETIME(6) NULL,
    CONSTRAINT fk_project_handover_task FOREIGN KEY (task_id) REFERENCES project_tasks(id),
    CONSTRAINT fk_project_handover_from FOREIGN KEY (from_user_id) REFERENCES users(id),
    CONSTRAINT fk_project_handover_to FOREIGN KEY (to_user_id) REFERENCES users(id),
    CONSTRAINT fk_project_handover_requester FOREIGN KEY (requested_by) REFERENCES users(id),
    CONSTRAINT ck_project_handover_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),
    INDEX ix_project_handover_pending (task_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_task_activity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    report_user_id BIGINT NOT NULL,
    report_team_id BIGINT NULL,
    event_type VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL,
    project_name VARCHAR(160) NOT NULL,
    task_title VARCHAR(200) NOT NULL,
    task_status VARCHAR(20) NOT NULL,
    progress INT NOT NULL,
    due_date DATE NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_project_activity_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_activity_task FOREIGN KEY (task_id) REFERENCES project_tasks(id),
    CONSTRAINT fk_project_activity_actor FOREIGN KEY (actor_user_id) REFERENCES users(id),
    CONSTRAINT fk_project_activity_report_user FOREIGN KEY (report_user_id) REFERENCES users(id),
    INDEX ix_project_activity_week (report_user_id, occurred_at, task_id),
    INDEX ix_project_activity_task (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
