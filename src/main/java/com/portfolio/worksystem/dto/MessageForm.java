package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MessageForm(
        @NotBlank(message = "메시지 내용을 입력하세요.")
        @Size(max = 2000, message = "메시지는 2000자 이하로 입력하세요.")
        String body
) {
}