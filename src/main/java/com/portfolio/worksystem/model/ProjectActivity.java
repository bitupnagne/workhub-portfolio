package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import java.time.LocalDate;
import lombok.Data;

@Data
public class ProjectActivity {
    private Long id;
    private Long projectId;
    private Long taskId;
    private Long actorUserId;
    private String actorName;
    private Long reportUserId;
    private Long reportTeamId;
    private String eventType;
    private String summary;
    private String projectName;
    private String taskTitle;
    private String taskStatus;
    private int progress;
    private LocalDate dueDate;
    private LocalDateTime occurredAt;
}
