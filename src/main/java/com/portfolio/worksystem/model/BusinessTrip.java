package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class BusinessTrip {
    private Long id;
    private Long userId;
    private String username;
    private String memberName;
    private Long teamId;
    private String teamName;
    private LocalDate startDate;
    private LocalDate endDate;
    private String destination;
    private String purpose;
    private BigDecimal transportCost;
    private BigDecimal lodgingCost;
    private BigDecimal mealCost;
    private BigDecimal dailyAllowance;
    private BigDecimal etcCost;
    private String costNote;
    private Long receiptId;
    private String receiptOriginalFilename;
    private BusinessTripStatus status;
    private Integer currentApprovalStep;
    private Long approverId;
    private String approverName;
    private LocalDateTime approvedAt;
    private String rejectedReason;
    private LocalDateTime createdAt;

    public boolean isPending() {
        return status == BusinessTripStatus.PENDING;
    }

    public BigDecimal getTotalCost() {
        return zero(transportCost).add(zero(lodgingCost)).add(zero(mealCost)).add(zero(dailyAllowance)).add(zero(etcCost));
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
