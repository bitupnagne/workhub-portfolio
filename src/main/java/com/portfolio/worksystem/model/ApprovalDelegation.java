package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ApprovalDelegation {
    private Long id;
    private Long approverUserId;
    private String approverName;
    private Long delegateUserId;
    private String delegateName;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    private Boolean active;
    private LocalDateTime createdAt;
}
