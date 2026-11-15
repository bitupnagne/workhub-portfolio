package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record UserUpdateForm(
        @NotNull Long id,
        String employeeNumber,
        @NotBlank String name,
        Long teamId,
        @NotBlank String dept,
        String extensionNumber,
        String mobileNumber,
        String jobTitle,
        Long gradeId,
        @Email String email,
        LocalDate hireDate,
        LocalDate loaStart,
        LocalDate loaEnd,
        boolean active,
        boolean contractor
) {
}
