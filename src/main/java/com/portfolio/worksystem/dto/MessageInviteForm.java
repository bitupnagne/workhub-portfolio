package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record MessageInviteForm(
        @NotEmpty(message = "초대할 직원을 선택하세요.")
        List<Long> userIds
) {
}
