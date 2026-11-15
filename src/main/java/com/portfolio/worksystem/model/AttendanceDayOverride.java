package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Data;

@Data
public class AttendanceDayOverride {
    private Long id;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String note;
    private Long createdBy;
}