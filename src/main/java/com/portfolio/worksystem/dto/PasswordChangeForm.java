package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeForm(
        @NotBlank(message = "현재 비밀번호를 입력하세요")
        String currentPassword,
        @NotBlank(message = "새 비밀번호를 입력하세요")
        @Size(min = com.portfolio.worksystem.security.PasswordPolicy.MIN_LENGTH, message = "새 비밀번호는 9자 이상이어야 합니다")
        String newPassword,
        @NotBlank(message = "새 비밀번호 확인을 입력하세요")
        String confirmPassword
) {
}
