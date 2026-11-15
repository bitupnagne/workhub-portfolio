package com.portfolio.worksystem.dto;

import java.time.LocalDate;

public record FlexTimeRequestForm(
        String shiftGroup,
        LocalDate startDate,
        LocalDate endDate,
        String reason
) {
}
