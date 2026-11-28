package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class BusinessTripReceipt {
    private Long id;
    private Long tripId;
    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private Long fileSize;
    private Long uploadedBy;
    private LocalDateTime createdAt;
}
