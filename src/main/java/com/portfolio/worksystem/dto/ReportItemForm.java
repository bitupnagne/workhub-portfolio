package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.ItemStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ReportItemForm(
        Long id,
        @NotBlank String content,
        LocalDate dueDate,
        String cycle,
        @NotNull ItemStatus status,
        @NotBlank String weekKey
) {
}
