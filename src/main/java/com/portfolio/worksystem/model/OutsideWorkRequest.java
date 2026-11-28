package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import lombok.Data;

/**
 * [OW] 외근 신청 — 정해진 출근·퇴근 시각에 걸치는 외근의 출퇴근 유예.
 * 상태는 시간외 근무와 같은 값(승인 대기·승인·반려·취소)을 쓴다.
 */
@Data
public class OutsideWorkRequest {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private Long id;
    private Long userId;
    private String memberName;
    private Long teamId;
    private String teamName;
    private LocalDate workDate;
    /** 회사에서 나간 시각. */
    private LocalTime outTime;
    /** 외근이 끝난 시각 — 승인되면 이 시각이 그날 퇴근이 된다. */
    private LocalTime endTime;
    private String place;
    private String reason;
    private OvertimeStatus status;
    private Integer currentApprovalStep;
    private Long decidedBy;
    private String rejectedReason;
    private Boolean attendanceApplied;
    private LocalDateTime createdAt;

    public boolean isPending() {
        return status == OvertimeStatus.PENDING;
    }

    public String getTimeText() {
        return (outTime == null ? "--:--" : outTime.format(TIME)) + " ~ " + (endTime == null ? "--:--" : endTime.format(TIME));
    }

    public String getStatusLabel() {
        return status == null ? "" : status.getLabel();
    }
}
