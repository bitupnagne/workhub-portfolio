package com.portfolio.worksystem.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record AttendanceCorrectionForm(
        LocalDate workDate,
        LocalTime requestedCheckIn,
        LocalTime requestedCheckOut,
        String reason
) {
}
