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
}