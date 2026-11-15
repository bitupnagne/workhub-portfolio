package com.portfolio.worksystem.dto;

public record DesktopAttendancePayload(
        boolean checkedIn,
        boolean checkedOut,
        String checkInAt,
        String checkOutAt,
        String status,
        String statusLabel,
        /** [W7] 오늘 기준 근무시간 표기 — 요일별 근무시간표(C3)·시차출근 반영, 예: "09:00~13:00 (금요일 반일)" */
        String scheduleText,
        /** [W8] 오늘 출근 체크 불가 사유(휴무일·휴직·승인 출장/휴가 등) — null이면 체크 가능. 웹 A1과 동일 규칙. */
        String blockReason
) {
}
