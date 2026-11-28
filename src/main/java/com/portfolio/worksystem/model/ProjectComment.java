package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ProjectComment {
    private Long id;
    private Long taskId;
    private Long userId;
    private String userName;
    private String body;
    private LocalDateTime createdAt;
}
