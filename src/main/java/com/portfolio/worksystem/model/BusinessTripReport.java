package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** [UX47] 출장보고서 — 결재 완료된 출장(business_trips)에 1건, 별도 결재(TRIP_REPORT). */
@Data
public class BusinessTripReport {
    private Long id;
    private Long tripId;
    private String content;
    private Boolean expenseChanged;
    private BusinessTripStatus status;
    private Integer currentApprovalStep;
    private Long decidedBy;
    private String rejectedReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 조인 필드(business_trips / users / teams)
    private Long userId;
    private String memberName;
    private String teamName;
    private String destination;
    private String purpose;
    private LocalDate tripStartDate;
    private LocalDate tripEndDate;

    public boolean isPending() {
        return status == BusinessTripStatus.PENDING;
    }
}
