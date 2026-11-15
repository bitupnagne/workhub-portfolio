package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ApprovalDocumentSnapshot {
    private Long id;
    private ApprovalDocumentType documentType;
    private Long documentId;
    private String documentKind;
    private String documentTitle;
    private String requesterName;
    private String teamName;
    private String statusLabel;
    private String snapshotHtml;
    private String storedFilename;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private Long generatedBy;
    private LocalDateTime createdAt;
}
