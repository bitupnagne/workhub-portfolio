package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class LeaveRequest {
    private Long id;
    private Long userId;
    private String username;
    private String memberName;
    private Long teamId;
    private String teamName;
    private Boolean contractor;
    /** [UX11] 휴가 종류 코드(leave_types.code) — enum 폐지, 관리자 신규 종류 추가 지원 */
    private String type;
    /** leave_types.label 조인(코드 미등록 시 null → getTypeLabel()이 코드로 폴백) */
    private String typeLabel;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal days;
    /** 신청 당시 실제 차감한 연차. 종류 설정 변경 후에도 이 값만 복구한다. */
    private BigDecimal annualDeductedDays;
    private String reason;
    private LeaveStatus status;
    private Integer currentApprovalStep;
    private Long approverId;
    private String approverName;
    private LocalDateTime approvedAt;
    private String rejectedReason;
    private String cancelReason;
    private LocalDateTime createdAt;

    public boolean isPending() {
        return status == LeaveStatus.PENDING;
    }

    public String getTypeLabel() {
        return typeLabel == null || typeLabel.isBlank() ? type : typeLabel;
    }
}
