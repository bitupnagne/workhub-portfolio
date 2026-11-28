package com.portfolio.worksystem.dto;

import java.time.LocalDate;

public record LeaveRequestForm(
        /* [UX11] 휴가 종류 코드(leave_types.code) — enum 폐지 */
        String type,
        LocalDate startDate,
        LocalDate endDate,
        String reason
) {
}
