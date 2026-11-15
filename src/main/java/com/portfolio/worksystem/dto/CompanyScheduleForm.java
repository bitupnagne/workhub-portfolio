package com.portfolio.worksystem.dto;

import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

public record CompanyScheduleForm(
        String title,
        String description,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
        String color
) {
}
