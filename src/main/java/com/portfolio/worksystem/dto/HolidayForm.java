package com.portfolio.worksystem.dto;

import java.time.LocalDate;

public record HolidayForm(
        LocalDate holidayDate,
        String name,
        String category  // "COMPANY" | "PUBLIC" — 기본은 COMPANY
) {
}