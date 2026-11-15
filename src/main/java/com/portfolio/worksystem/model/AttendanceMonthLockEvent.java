package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

/** [A4] 근태 월 마감/해제 이벤트(attendance_month_lock_events, V74) — 감사 이력 겸 상태 원장. */
@Data
public class AttendanceMonthLockEvent {
    private Long id;
    /** yyyy-MM */
    private String ym;
    /** LOCK | UNLOCK */
    private String action;
    private Long actedBy;
    private String actedByName;
    private LocalDateTime createdAt;
}
