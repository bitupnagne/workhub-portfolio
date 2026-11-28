package com.portfolio.worksystem.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record AttendanceOverrideForm(
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        String note
) {
}