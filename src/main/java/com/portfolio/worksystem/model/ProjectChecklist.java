package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ProjectChecklist {
    private Long id;
    private Long taskId;
    private String text;
    private boolean completed;
    private LocalDateTime createdAt;
}
