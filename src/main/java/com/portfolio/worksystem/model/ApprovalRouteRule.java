package com.portfolio.worksystem.model;

import lombok.Data;

@Data
public class ApprovalRouteRule {
    private Long id;
    private ApprovalDocumentType documentType;
    private Long scopeTeamId;
    private String scopeTeamName;
    private RequesterLevel requesterLevel;
    private Integer stepOrder;
    private ApproverType approverType;
    private Long approverUserId;
    private String approverName;
    private Long approverTeamId;
    private String approverTeamName;
    private Long receiverTeamId;
    private String receiverTeamName;
    private java.util.List<Long> receiverTeamIds;
    private Boolean active;
}
