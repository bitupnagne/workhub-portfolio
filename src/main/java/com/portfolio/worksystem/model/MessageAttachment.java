package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class MessageAttachment {
    private Long id;
    private Long messageId;
    private Long roomId;
    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private Long fileSize;
    private String attachmentType;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }
}
