package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.worksystem.model.AttendanceDayOverride;
import com.portfolio.worksystem.model.AttendanceWeekDay;
import com.portfolio.worksystem.model.ShiftGroupDef;
import com.portfolio.worksystem.service.HrService.WorkSchedule;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class WorkScheduleResolverTest {

    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_END = LocalTime.of(18, 0);

    private AttendanceDayOverride override(LocalTime start, LocalTime end, String note) {
        AttendanceDayOverride o = new AttendanceDayOverride();
        o.setStartTime(start);
        o.setEndTime(end);
        o.setNote(note);
        return o;
    }

    private ShiftGroupDef shift(String code, LocalTime start, LocalTime end) {
        ShiftGroupDef g = new ShiftGroupDef();
        g.setCode(code);
        g.setLabel(code + "조");
        g.setStartTime(start);
        g.setEndTime(end);
        g.setActive(Boolean.TRUE);
        return g;
    }

    private AttendanceWeekDay weekDay(int dayOfWeek, String workType, LocalTime start, LocalTime end) {
        AttendanceWeekDay d = new AttendanceWeekDay();
        d.setDayOfWeek(dayOfWeek);
        d.setWorkType(workType);
        d.setStartTime(start);
        d.setEndTime(end);
        return d;
    }

    @Test
    void defaultWhenNoOverrideAndNoShift() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END, null, null);

        assertThat(schedule.startTime()).isEqualTo(DEFAULT_START);
        assertThat(schedule.endTime()).isEqualTo(DEFAULT_END);
        assertThat(schedule.override()).isFalse();
        assertThat(schedule.note()).isEqualTo("기본 근무시간");
    }

    @Test
    void shiftWithFixedHoursUsesThem() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END, null,
                shift("A", LocalTime.of(8, 0), LocalTime.of(17, 0)));

        assertThat(schedule.startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(schedule.endTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(schedule.override()).isFalse();
        assertThat(schedule.note()).contains("시차출근");
    }

    @Test
    void shiftWithoutTimesFollowsCompanyDefault() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(
                LocalTime.of(9, 30), LocalTime.of(18, 30), null, shift("B", null, null));

        assertThat(schedule.startTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(schedule.endTime()).isEqualTo(LocalTime.of(18, 30));
        assertThat(schedule.note()).contains("시차출근");
    }

    @Test
    void overrideUsesItsOwnHoursAndNote() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END,
                override(LocalTime.of(13, 0), LocalTime.of(17, 0), "전사 행사"), null);

        assertThat(schedule.startTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(schedule.endTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(schedule.override()).isTrue();
        assertThat(schedule.note()).isEqualTo("전사 행사");
    }

    @Test
    void overrideWithBlankNoteFallsBackToLabel() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END,
                override(LocalTime.of(13, 0), LocalTime.of(17, 0), "  "), null);

        assertThat(schedule.note()).isEqualTo("전사 일자예외");
    }

    @Test
    void companyOverrideWinsOverApprovedShift() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END,
                override(LocalTime.of(13, 0), LocalTime.of(17, 0), "전사 행사"),
                shift("A", LocalTime.of(8, 0), LocalTime.of(17, 0)));

        assertThat(schedule.startTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(schedule.endTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(schedule.override()).isTrue();
        assertThat(schedule.note()).isEqualTo("전사 행사");
    }

    /* ── [C3] 요일별 근무시간표(V70) ── */

    @Test
    void weekDayCustomTimesReplaceDefault() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END, null, null,
                weekDay(1, "FULL", LocalTime.of(10, 0), LocalTime.of(19, 0)));

        assertThat(schedule.startTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(schedule.endTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(schedule.note()).isEqualTo("월요일 근무시간");
    }

    @Test
    void halfDayWithoutTimesEndsFourHoursAfterStart() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END, null, null,
                weekDay(5, "HALF", null, null));

        assertThat(schedule.startTime()).isEqualTo(DEFAULT_START);
        assertThat(schedule.endTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(schedule.note()).contains("금요일 반일");
    }

    @Test
    void halfDayCombinesWithShiftStart() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END, null,
                shift("A", LocalTime.of(8, 0), LocalTime.of(17, 0)),
                weekDay(5, "HALF", null, null));

        assertThat(schedule.startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(schedule.endTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(schedule.note()).contains("시차출근");
        assertThat(schedule.note()).contains("반일");
    }

    @Test
    void overrideWinsOverWeekDay() {
        WorkSchedule schedule = WorkScheduleResolver.resolve(DEFAULT_START, DEFAULT_END,
                override(LocalTime.of(13, 0), LocalTime.of(17, 0), "전사 행사"), null,
                weekDay(5, "HALF", null, null));

        assertThat(schedule.startTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(schedule.endTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(schedule.override()).isTrue();
    }
}
