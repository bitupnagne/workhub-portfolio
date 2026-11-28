package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** [A3] HR 직권 근태 정정 감사 로그(attendance_admin_adjustments, V72). */
@Data
public class AttendanceAdminAdjustment {
    private Long id;
    private Long userId;
    private LocalDate workDate;
    private LocalDateTime beforeCheckInAt;
    private LocalDateTime beforeCheckOutAt;
    private AttendanceStatus beforeStatus;
    private LocalDateTime afterCheckInAt;
    private LocalDateTime afterCheckOutAt;
    private AttendanceStatus afterStatus;
    private String reason;
    private Long adjustedBy;
    private LocalDateTime createdAt;
}
