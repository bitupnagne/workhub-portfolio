package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ProfileInfoForm(
        @NotBlank String dept,
        String extensionNumber,
        String mobileNumber,
        String jobTitle,
        @Email String email
) {
}
