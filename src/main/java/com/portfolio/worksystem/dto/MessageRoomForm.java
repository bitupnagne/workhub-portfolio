package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MessageRoomForm(
        @Size(max = 160, message = "메시지방 이름은 160자 이하로 입력하세요.")
        String title,
        @NotEmpty(message = "대화할 직원을 선택하세요.")
        List<Long> recipientIds,
        @Size(max = 2000, message = "첫 메시지는 2000자 이하로 입력하세요.")
        String firstMessage
) {
}