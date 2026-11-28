package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.Data;

/**
 * [OT] 시간외 출퇴근 기록(overtime_sessions, V89) — 정규 근무 밖에서 찍힌 출퇴근.
 *
 * <p>근태 판정(지각·조퇴)은 하지 않는다. 승인된 시간외 신청과 겹치는 만큼만 인정 시간이 된다.
 */
@Data
public class OvertimeSession {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private Long id;
    private Long userId;
    private LocalDate workDate;
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    private String source;

    public boolean isOpen() {
        return checkOutAt == null;
    }

    public String getTimeText() {
        if (checkInAt == null) {
            return "-";
        }
        if (checkOutAt == null) {
            return checkInAt.format(TIME) + " ~ 근무중";
        }
        boolean nextDay = !checkOutAt.toLocalDate().equals(checkInAt.toLocalDate());
        return checkInAt.format(TIME) + " ~ " + (nextDay ? "익일 " : "") + checkOutAt.format(TIME);
    }
}
