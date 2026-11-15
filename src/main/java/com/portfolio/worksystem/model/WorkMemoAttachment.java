package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WorkMemoAttachment {
    private Long id;
    private Long memoId;
    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private long fileSize;
    private String attachmentType; // IMAGE or FILE
    private LocalDateTime createdAt;
}
