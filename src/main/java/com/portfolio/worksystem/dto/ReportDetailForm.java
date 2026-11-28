package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ReportDetailForm(
        Long id,
        @NotNull Long itemId,
        @NotBlank String text,
        LocalDate dueDate,
        String cycle,
        @NotBlank String weekKey
) {
}
