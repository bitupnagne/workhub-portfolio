package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ApprovalDelegationForm(
        @NotNull Long delegateUserId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        String reason
) {
}
