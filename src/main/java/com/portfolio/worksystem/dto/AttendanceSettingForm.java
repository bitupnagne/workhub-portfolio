package com.portfolio.worksystem.dto;

import java.time.LocalTime;

public record AttendanceSettingForm(
        LocalTime defaultStartTime,
        LocalTime defaultEndTime
) {
}