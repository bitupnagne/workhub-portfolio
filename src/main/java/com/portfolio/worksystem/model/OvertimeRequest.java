package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.Data;

/**
 * [OT] 시간외 근무 신청서(overtime_requests, V88).
 *
 * <p>{@code workDate} 는 그 근무가 속한 날이다. 자정을 넘긴 야근은 {@code endAt} 이 다음 날이어도
 * 근무일은 전날이다.
 */
@Data
public class OvertimeRequest {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private Long id;
    private Long userId;
    private String memberName;
    private Long teamId;
    private String teamName;
    private LocalDate workDate;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer breakMinutes;
    /** PRE(사전) / POST(사후) */
    private String requestKind;
    private OvertimeDayType dayType;
    /** 휴게를 뺀 신청 시간(분). */
    private Integer totalMinutes;
    /** 그중 야간 시간대에 걸친 분. */
    private Integer nightMinutes;
    private String reason;
    private OvertimeStatus status;
    private Integer currentApprovalStep;
    private Long decidedBy;
    private String rejectedReason;
    private LocalDateTime createdAt;

    public boolean isPending() {
        return status == OvertimeStatus.PENDING;
    }

    public boolean isPostRequest() {
        return "POST".equals(requestKind);
    }

    public String getKindLabel() {
        return isPostRequest() ? "사후 신청" : "사전 신청";
    }

    /** 예: 18:00 ~ 21:30, 자정을 넘기면 '익일 01:00'. */
    public String getTimeText() {
        if (startAt == null || endAt == null) {
            return "-";
        }
        boolean nextDay = !endAt.toLocalDate().equals(startAt.toLocalDate());
        return startAt.format(TIME) + " ~ " + (nextDay ? "익일 " : "") + endAt.format(TIME);
    }

    public String getDurationText() {
        return minutesText(totalMinutes);
    }

    public String getNightText() {
        return minutesText(nightMinutes);
    }

    public static String minutesText(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return "0분";
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + "분";
        }
        return rest == 0 ? hours + "시간" : hours + "시간 " + rest + "분";
    }
}
