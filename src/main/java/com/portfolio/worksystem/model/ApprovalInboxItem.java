package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ApprovalInboxItem {
    private Long stepId;
    private ApprovalDocumentType documentType;
    private Long documentId;
    private Integer stepOrder;
    private Long requesterId;
    private String requesterName;
    private String teamName;
    private String title;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private Long approverUserId;
    private String approverName;
    private Long delegateUserId;
    private String delegateName;
    private Long receiptId;
    private String receiptOriginalFilename;
    private String receiverTeamName;
    private LocalDateTime createdAt;
}
