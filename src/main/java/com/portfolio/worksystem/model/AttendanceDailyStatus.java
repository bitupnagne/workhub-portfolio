package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Data;

@Data
public class AttendanceDailyStatus {
    private Long userId;
    private String memberName;
    private Long teamId;
    private String teamName;
    private Boolean contractor;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    private AttendanceStatus status;
    private String note;
    private String scheduleNote;
    private String holidayName;
    private String tripDestination;
    private Long correctionId;
    private AttendanceCorrectionStatus correctionStatus;
    private boolean correctionPending;
    /** 아직 오지 않은 근무일 — 미출근 판정도, 정정 신청도 대상이 아니다. */
    private boolean future;

    /** 화면 표시용 상태명. 미래 근무일은 '미출근'이 아니라 '예정'으로 보인다. */
    public String getStatusLabel() {
        if (status == null) {
            return "";
        }
        return future && status == AttendanceStatus.SCHEDULED ? "예정" : status.getLabel();
    }
}
