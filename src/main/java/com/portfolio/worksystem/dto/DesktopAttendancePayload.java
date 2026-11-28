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
        String blockReason,
        /** [OT] 지금 출근을 누르면 시간외 기록이 되는 사유(휴무일·기준 퇴근시각 이후·퇴근 후 재출근) — 정규 출근이면 null. */
        String overtimeReason,
        /** [OT] 아직 퇴근을 찍지 않은 시간외 기록의 출근 시각 — 없으면 null. */
        String overtimeCheckInAt,
        /** [OT] 이번 달 신청하지 않은 시간외 근무 건수(웹 안내 모달과 같은 값) — 1.0.28+ 앱이 안내 줄로 보여 준다. */
        int unrequestedOvertimeCount
) {
    /** 시간외 정보가 없는 기본 응답(기존 생성 경로 호환). */
    public DesktopAttendancePayload(boolean checkedIn, boolean checkedOut, String checkInAt, String checkOutAt,
                                    String status, String statusLabel, String scheduleText, String blockReason,
                                    String overtimeReason, String overtimeCheckInAt) {
        this(checkedIn, checkedOut, checkInAt, checkOutAt, status, statusLabel, scheduleText, blockReason,
                overtimeReason, overtimeCheckInAt, 0);
    }
}
