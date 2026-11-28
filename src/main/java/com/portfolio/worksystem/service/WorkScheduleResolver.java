package com.portfolio.worksystem.service;

import com.portfolio.worksystem.model.AttendanceDayOverride;
import com.portfolio.worksystem.model.AttendanceWeekDay;
import com.portfolio.worksystem.model.ShiftGroupDef;
import com.portfolio.worksystem.service.HrService.WorkSchedule;
import java.time.LocalTime;

/**
 * 근무시간 해석 우선순위(순수 로직, DB 비의존):
 * 전사 일자예외 > 개인 승인 시차출근 > 요일별 근무시간표(V70) > 개인(전사) 기본 근무시간.
 * 요일 HALF(반일)는 최종 퇴근 시각을 절단한다(시차출근과 조합 가능 — 주 4.5일제는 전사 제도).
 * 회사 휴무일·요일 OFF는 별도 게이트에서 처리하므로 여기서 다루지 않는다.
 */
public final class WorkScheduleResolver {

    /** 반일 근무시간(시각 미지정 시 출근 후 4시간) */
    private static final long HALF_DAY_WORK_HOURS = 4;

    private WorkScheduleResolver() {
    }

    public static WorkSchedule resolve(LocalTime companyStart, LocalTime companyEnd,
                                       AttendanceDayOverride override, ShiftGroupDef approvedShift) {
        return resolve(companyStart, companyEnd, override, approvedShift, null);
    }

    public static WorkSchedule resolve(LocalTime companyStart, LocalTime companyEnd,
                                       AttendanceDayOverride override, ShiftGroupDef approvedShift,
                                       AttendanceWeekDay weekDay) {
        if (override != null) {
            String note = override.getNote() == null || override.getNote().isBlank()
                    ? "전사 일자예외"
                    : override.getNote();
            return new WorkSchedule(override.getStartTime(), override.getEndTime(), note, true);
        }
        LocalTime start = companyStart;
        LocalTime end = companyEnd;
        String note = "기본 근무시간";
        if (weekDay != null && weekDay.hasCustomTimes()) {
            start = weekDay.getStartTime();
            end = weekDay.getEndTime();
            note = weekDay.dayLabel() + "요일 근무시간";
        }
        if (approvedShift != null && !approvedShift.followsDefault()) {
            start = approvedShift.getStartTime();
            end = approvedShift.getEndTime();
        }
        if (approvedShift != null) {
            note = "시차출근 " + approvedShift.getLabel();
        }
        if (weekDay != null && weekDay.isHalf()) {
            LocalTime halfEnd = weekDay.hasCustomTimes() ? weekDay.getEndTime() : start.plusHours(HALF_DAY_WORK_HOURS);
            if (halfEnd.isBefore(end)) {
                end = halfEnd;
            }
            note = note + " · " + weekDay.dayLabel() + "요일 반일";
        }
        return new WorkSchedule(start, end, note, false);
    }
}
