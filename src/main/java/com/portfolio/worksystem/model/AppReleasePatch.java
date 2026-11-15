package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AppReleasePatch {
    private Long id;
    private Long releaseId;
    private String baseVersion;
    private String fileName;
    private String storedFileName;
    private String sha256;
    private Long fileSize;
    private LocalDateTime createdAt;
}
