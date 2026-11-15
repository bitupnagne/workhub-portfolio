package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Data;

@Data
public class AttendanceCorrection {
    private Long id;
    private Long userId;
    private String username;
    private String memberName;
    private Long teamId;
    private String teamName;
    private Boolean contractor;
    private LocalDate workDate;
    private Long attendanceRecordId;
    private LocalDateTime originalCheckInAt;
    private LocalDateTime originalCheckOutAt;
    private AttendanceStatus originalStatus;
    private LocalTime requestedCheckIn;
    private LocalTime requestedCheckOut;
    private String reason;
    private AttendanceCorrectionStatus status;
    private Integer currentApprovalStep;
    private Long approverId;
    private String approverName;
    private LocalDateTime approvedAt;
    private String rejectedReason;
    private LocalDateTime createdAt;

    public boolean isPending() {
        return status == AttendanceCorrectionStatus.PENDING;
    }
}
