package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Data;

@Data
public class AttendanceDailyStatus {
    private Long userId;
    private String memberName;
    private Long teamId;
    private String teamName;
    private Boolean contractor;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    private AttendanceStatus status;
    private String note;
    private String scheduleNote;
    private String holidayName;
    private String tripDestination;
    private Long correctionId;
    private AttendanceCorrectionStatus correctionStatus;
    private boolean correctionPending;
}
