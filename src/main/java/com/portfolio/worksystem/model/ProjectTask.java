package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.Data;

@Data
public class ProjectTask {
    private Long id;
    private Long projectId;
    private String title;
    private String description;
    private Long assigneeId;
    private String assigneeName;
    private ProjectTaskStatus status;
    private int progress;
    private LocalDate dueDate;
    private long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isOverdue() {
        return status != ProjectTaskStatus.DONE && dueDate != null
                && dueDate.isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }
    public String getStatusLabel() { return status == null ? "예정" : status.getLabel(); }
}
