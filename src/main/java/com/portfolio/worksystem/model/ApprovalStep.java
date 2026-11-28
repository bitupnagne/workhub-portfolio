package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ApprovalStep {
    private Long id;
    private ApprovalDocumentType documentType;
    private Long documentId;
    private Integer stepOrder;
    private Long approverUserId;
    private ApproverType approverType;
    private String approverName;
    private String approverDept;
    private String approverJobTitle;
    private Long delegateUserId;
    private String delegateName;
    private ApprovalStatus status;
    private Long actedBy;
    private String actedByName;
    private LocalDateTime actedAt;
    private Long postApprovedBy;
    private String postApprovedByName;
    private LocalDateTime postApprovedAt;
    private String rejectedReason;

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }
}
