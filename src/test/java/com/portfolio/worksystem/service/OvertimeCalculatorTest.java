package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.worksystem.model.CompanyHoliday;
import com.portfolio.worksystem.model.OvertimeDayType;
import com.portfolio.worksystem.model.OvertimeRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** [OT] 시간외 근무 시간 계산 — 야간 구간, 근무일 구분, 휴게 차감. */
class OvertimeCalculatorTest {

    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 0);

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute);
    }

    @Test
    void eveningOvertimeBeforeTenHasNoNightMinutes() {
        assertThat(OvertimeCalculator.nightMinutes(at(23, 18, 0), at(23, 21, 30), NIGHT_START, NIGHT_END)).isZero();
    }

    @Test
    void overtimePastTenCountsOnlyTheNightPart() {
        // 18:00~23:30 → 22:00 이후 90분만 야간
        assertThat(OvertimeCalculator.nightMinutes(at(23, 18, 0), at(23, 23, 30), NIGHT_START, NIGHT_END)).isEqualTo(90);
    }

    @Test
    void overtimeAcrossMidnightKeepsCountingUntilSix() {
        // 21:00~익일 07:00 → 22:00~06:00 8시간
        assertThat(OvertimeCalculator.nightMinutes(at(23, 21, 0), at(24, 7, 0), NIGHT_START, NIGHT_END)).isEqualTo(480);
    }

    @Test
    void earlyMorningStartBeforeSixIsNight() {
        // 새벽 조출 05:00~09:00 → 06:00 이전 60분
        assertThat(OvertimeCalculator.nightMinutes(at(23, 5, 0), at(23, 9, 0), NIGHT_START, NIGHT_END)).isEqualTo(60);
    }

    @Test
    void nightWindowWithinOneDayIsSupported() {
        // 야간을 00:00~06:00 으로 설정한 회사
        assertThat(OvertimeCalculator.nightMinutes(at(23, 23, 0), at(24, 2, 0), LocalTime.MIDNIGHT, NIGHT_END)).isEqualTo(120);
    }

    @Test
    void emptyOrReversedIntervalIsZero() {
        assertThat(OvertimeCalculator.minutesBetween(at(23, 18, 0), at(23, 18, 0))).isZero();
        assertThat(OvertimeCalculator.nightMinutes(at(23, 23, 0), at(23, 22, 0), NIGHT_START, NIGHT_END)).isZero();
        assertThat(OvertimeCalculator.nightMinutes(at(23, 22, 0), at(23, 23, 0), NIGHT_START, NIGHT_START)).isZero();
    }

    @Test
    void breakIsDeductedFromNightInProportion() {
        // 총 300분 중 야간 150분, 휴게 60분 → 야간에서 30분만 뺀다
        assertThat(OvertimeCalculator.nightAfterBreak(300, 150, 60)).isEqualTo(120);
        assertThat(OvertimeCalculator.nightAfterBreak(300, 0, 60)).isZero();
        assertThat(OvertimeCalculator.nightAfterBreak(300, 150, 0)).isEqualTo(150);
    }

    @Test
    void dayTypePriorityIsLaborDayThenPublicHolidayThenRestDay() {
        CompanyHoliday publicHoliday = new CompanyHoliday();
        publicHoliday.setCategory("PUBLIC");
        CompanyHoliday companyHoliday = new CompanyHoliday();
        companyHoliday.setCategory("COMPANY");

        assertThat(OvertimeCalculator.dayType(LocalDate.of(2026, 5, 1), publicHoliday, false)).isEqualTo(OvertimeDayType.LABOR_DAY);
        assertThat(OvertimeCalculator.dayType(LocalDate.of(2026, 12, 25), publicHoliday, false)).isEqualTo(OvertimeDayType.PUBLIC_HOLIDAY);
        assertThat(OvertimeCalculator.dayType(LocalDate.of(2026, 11, 20), companyHoliday, false)).isEqualTo(OvertimeDayType.HOLIDAY);
        assertThat(OvertimeCalculator.dayType(LocalDate.of(2026, 11, 21), null, true)).isEqualTo(OvertimeDayType.HOLIDAY);
        assertThat(OvertimeCalculator.dayType(LocalDate.of(2026, 11, 23), null, false)).isEqualTo(OvertimeDayType.WORKDAY);
    }

    @Test
    void requestTextShowsNextDayAndDuration() {
        OvertimeRequest request = new OvertimeRequest();
        request.setStartAt(at(23, 22, 0));
        request.setEndAt(at(24, 1, 0));
        request.setTotalMinutes(150);

        assertThat(request.getTimeText()).isEqualTo("22:00 ~ 익일 01:00");
        assertThat(request.getDurationText()).isEqualTo("2시간 30분");
        assertThat(OvertimeRequest.minutesText(60)).isEqualTo("1시간");
        assertThat(OvertimeRequest.minutesText(0)).isEqualTo("0분");
    }
}
