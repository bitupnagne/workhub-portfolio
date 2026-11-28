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
    /** [DLG] 이 지정을 만든 부재 신청(LEAVE·BUSINESS_TRIP·LOA·OUTSIDE_WORK) — 직접 지정이면 null. */
    private String sourceType;
    private Long sourceId;

    /** 화면 표시용 상태. */
    public String getStateLabel() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        if (!Boolean.TRUE.equals(active)) {
            return sourceType != null ? "예정" : "해제";
        }
        if (endDate != null && endDate.isBefore(today)) {
            return "종료";
        }
        if (startDate != null && startDate.isAfter(today)) {
            return "적용 예정";
        }
        return "적용 중";
    }

    public String getSourceLabel() {
        if (sourceType == null) {
            return "직접 지정";
        }
        return switch (sourceType) {
            case "LEAVE" -> "휴가 신청";
            case "TRIP" -> "출장 신청";
            case "LOA" -> "휴직 신청";
            case "OUTSIDE_WORK" -> "외근 신청";
            default -> sourceType;
        };
    }
    private LocalDateTime createdAt;
}
