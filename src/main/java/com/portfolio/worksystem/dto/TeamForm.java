package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotBlank;

public record TeamForm(
        @NotBlank String code,
        @NotBlank String name,
        Long parentId,
        String nodeType
) {
}