package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AttendanceRecord {
    private Long id;
    private Long userId;
    private String memberName;
    private Long teamId;
    private String teamName;
    private LocalDate workDate;
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    private AttendanceStatus status;
    private String note;
    /** [OT] 기록이 들어온 경로 — WEB/DESKTOP/DEVICE/CORRECTION. 계산은 시각만 보고, 이 값은 증적용이다. */
    private String source;
}