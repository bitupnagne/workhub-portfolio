package com.portfolio.worksystem.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import lombok.Data;

/** 요일별 근무시간표(attendance_week_schedule, V70) — 주 4.5일제 등 주간 패턴. */
@Data
public class AttendanceWeekDay {
    /** 1=월 ~ 7=일 (DayOfWeek.getValue()) */
    private Integer dayOfWeek;
    /** FULL(전일) | HALF(반일) | OFF(휴무) */
    private String workType;
    private LocalTime startTime;
    private LocalTime endTime;

    public boolean isOff() {
        return "OFF".equalsIgnoreCase(workType);
    }

    public boolean isHalf() {
        return "HALF".equalsIgnoreCase(workType);
    }

    public boolean hasCustomTimes() {
        return startTime != null && endTime != null;
    }

    public String dayLabel() {
        if (dayOfWeek == null) {
            return "-";
        }
        return switch (DayOfWeek.of(dayOfWeek)) {
            case MONDAY -> "월";
            case TUESDAY -> "화";
            case WEDNESDAY -> "수";
            case THURSDAY -> "목";
            case FRIDAY -> "금";
            case SATURDAY -> "토";
            case SUNDAY -> "일";
        };
    }
}
