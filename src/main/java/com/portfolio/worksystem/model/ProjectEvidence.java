package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ProjectEvidence {
    private Long id;
    private Long projectId;
    private Long taskId;
    private String kind;
    private String title;
    private String url;
    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private long sizeBytes;
    private Long uploadedBy;
    private String uploadedByName;
    private LocalDateTime createdAt;
}
