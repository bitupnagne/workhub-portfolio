package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ProjectHandover {
    private Long id;
    private Long taskId;
    private Long fromUserId;
    private String fromUserName;
    private Long toUserId;
    private String toUserName;
    private Long requestedBy;
    private String requestedByName;
    private String summary;
    private String status;
    private String response;
    private LocalDateTime requestedAt;
    private LocalDateTime respondedAt;
}
