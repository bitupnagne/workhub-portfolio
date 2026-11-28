package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.worksystem.model.OvertimeDayType;
import com.portfolio.worksystem.model.OvertimeSetting;
import com.portfolio.worksystem.service.OvertimePayCalculator.Line;
import com.portfolio.worksystem.service.OvertimePayCalculator.Presence;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** [OT] 인정 시간과 수당 — 신청과 실제 기록의 겹침, 연장 기준, 휴일 초과, 야간, 금액. */
class OvertimePayCalculatorTest {

    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(6, 0);

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute);
    }

    private static Line weekday(LocalDateTime start, LocalDateTime end, List<Presence> presences,
                                boolean anyRecord, int regularWorked) {
        return OvertimePayCalculator.recognize(start, end, OvertimeDayType.WORKDAY, presences, anyRecord,
                regularWorked, 480, NIGHT_START, NIGHT_END, 8);
    }

    private static OvertimeSetting setting() {
        OvertimeSetting setting = new OvertimeSetting();
        setting.setRateExtended(new BigDecimal("1.50"));
        setting.setRateNight(new BigDecimal("0.50"));
        setting.setRateHoliday(new BigDecimal("1.50"));
        setting.setRateHolidayOver(new BigDecimal("2.00"));
        setting.setRatePublicHoliday(new BigDecimal("1.50"));
        setting.setRateLaborDay(new BigDecimal("2.50"));
        return setting;
    }

    @Test
    void recognizedTimeIsTheOverlapOfRequestAndActualPresence() {
        // 신청 18:00~21:00, 실제 퇴근 20:30 → 2시간 30분만 인정
        Line line = weekday(at(23, 18, 0), at(23, 21, 0),
                List.of(new Presence(at(23, 9, 0), at(23, 20, 30))), true, 480);

        assertThat(line.recognizedMinutes()).isEqualTo(150);
        assertThat(line.extendedMinutes()).isEqualTo(150);
        assertThat(line.basis()).isEqualTo("기록과 겹친 시간만 인정");
    }

    @Test
    void workingPastTheApprovedEndIsNotRecognized() {
        // 신청 18:00~21:00, 실제 퇴근 21:40 → 승인된 3시간까지만
        Line line = weekday(at(23, 18, 0), at(23, 21, 0),
                List.of(new Presence(at(23, 9, 0), at(23, 21, 40))), true, 480);

        assertThat(line.recognizedMinutes()).isEqualTo(180);
        assertThat(line.basis()).isEqualTo("출퇴근 기록과 일치");
    }

    @Test
    void noAttendanceRecordAtAllMeansTheRequestedTimeStands() {
        // 출퇴근을 이 시스템에서 찍지 않는 운영 — 기록이 없는 것이 정상이다
        Line line = weekday(at(23, 18, 0), at(23, 21, 0), List.of(), false, 480);

        assertThat(line.recognizedMinutes()).isEqualTo(180);
        assertThat(line.basis()).contains("신청 시간 인정");
    }

    @Test
    void recordsExistButNotDuringTheRequestMeansNothingIsRecognized() {
        // 그날 18:00 에 퇴근했는데 19:00~21:00 을 신청해 승인받은 경우
        Line line = weekday(at(23, 19, 0), at(23, 21, 0),
                List.of(new Presence(at(23, 9, 0), at(23, 18, 0))), true, 480);

        assertThat(line.recognizedMinutes()).isZero();
        assertThat(line.basis()).isEqualTo("신청 시간에 출퇴근 기록 없음");
    }

    @Test
    void weekdayOvertimeOnAShortDayFillsTheStandardFirst() {
        // 지각해 정규 근무 7시간만 한 날의 2시간 → 1시간은 기준 내, 1시간만 연장
        Line line = weekday(at(23, 18, 0), at(23, 20, 0),
                List.of(new Presence(at(23, 10, 0), at(23, 20, 0))), true, 420);

        assertThat(line.withinStandardMinutes()).isEqualTo(60);
        assertThat(line.extendedMinutes()).isEqualTo(60);
    }

    @Test
    void breakFollowsActualPresenceNotTheRequest() {
        // 6시간 신청(휴게 30분 대상)이지만 실제로는 2시간만 근무 → 휴게 없이 2시간 인정
        Line line = weekday(at(23, 18, 0), at(24, 0, 0),
                List.of(new Presence(at(23, 9, 0), at(23, 20, 0))), true, 480);

        assertThat(line.recognizedMinutes()).isEqualTo(120);
    }

    @Test
    void reachingFourOrEightHoursAllowsLeavingWithoutWaitingOutTheBreak() {
        // 정확히 4시간 → 4시간 전부. 4시간 20분 머물러도 4시간, 4시간 30분부터 30분 차감.
        assertThat(OvertimeCalculator.autoBreakMinutes(240)).isZero();
        assertThat(OvertimeCalculator.autoBreakMinutes(260)).isEqualTo(20);
        assertThat(OvertimeCalculator.autoBreakMinutes(270)).isEqualTo(30);
        assertThat(OvertimeCalculator.autoBreakMinutes(480)).isEqualTo(30);
        // 8시간 근무도 같다: 8시간을 채운 시점(머문 8시간 30분)에 퇴근 가능, 그 뒤 30분까지는 휴게로 가정.
        assertThat(OvertimeCalculator.autoBreakMinutes(510)).isEqualTo(30);
        assertThat(OvertimeCalculator.autoBreakMinutes(525)).isEqualTo(45);
        assertThat(OvertimeCalculator.autoBreakMinutes(540)).isEqualTo(60);
        assertThat(OvertimeCalculator.autoBreakMinutes(720)).isEqualTo(60);
        // 인정 시간은 머문 시간이 늘어도 줄지 않는다.
        int previous = 0;
        for (int span = 0; span <= 900; span++) {
            int recognized = span - OvertimeCalculator.autoBreakMinutes(span);
            assertThat(recognized).isGreaterThanOrEqualTo(previous);
            previous = recognized;
        }

        Line line = weekday(at(23, 18, 0), at(23, 22, 0),
                List.of(new Presence(at(23, 9, 0), at(23, 22, 0))), true, 480);
        assertThat(line.recognizedMinutes()).isEqualTo(240);
    }

    @Test
    void holidayWorkSplitsAtTheOverThresholdAndCountsNightOnTop() {
        // 토요일 12:00~24:00 (12시간 중 휴게 1시간 자동 차감 = 11시간) → 8시간 휴일 + 3시간 휴일 초과
        Line line = OvertimePayCalculator.recognize(at(28, 12, 0), at(29, 0, 0), OvertimeDayType.HOLIDAY,
                List.of(new Presence(at(28, 12, 0), at(29, 0, 0))), true, 0, 480, NIGHT_START, NIGHT_END, 8);

        assertThat(line.holidayMinutes()).isEqualTo(480);
        assertThat(line.holidayOverMinutes()).isEqualTo(180);
        // 22시 이후 2시간 중 휴게 비율(1/12)만큼 뺀 110분이 야간
        assertThat(line.nightMinutes()).isEqualTo(110);
        // 8×1.5 + 3×2.0 = 18시간(1080분) + 야간 110분×0.5 = 55분
        assertThat(OvertimePayCalculator.weightedMinutes(line, OvertimeDayType.HOLIDAY, setting()))
                .isEqualByComparingTo(BigDecimal.valueOf(1080 + 55));
    }

    @Test
    void timeInsideRegularHoursIsNeverRecognizedAsOvertime() {
        Presence regular = new Presence(at(23, 9, 0), at(23, 18, 0));
        // 평일 13:00~17:00 — 전부 정규 근무시간 안 → 인정 없음(급여와 수당으로 두 번 잡히지 않는다)
        Line inside = OvertimePayCalculator.recognize(at(23, 13, 0), at(23, 17, 0), OvertimeDayType.WORKDAY,
                List.of(new Presence(at(23, 9, 0), at(23, 18, 0))), true, regular, 480, 480, NIGHT_START, NIGHT_END, 8);
        assertThat(inside.recognizedMinutes()).isZero();
        assertThat(inside.basis()).isEqualTo("기준 근무시간과 겹쳐 인정 시간 없음");

        // 17:00~20:00 — 18:00 이후 2시간만 인정
        Line straddle = OvertimePayCalculator.recognize(at(23, 17, 0), at(23, 20, 0), OvertimeDayType.WORKDAY,
                List.of(new Presence(at(23, 9, 0), at(23, 20, 0))), true, regular, 480, 480, NIGHT_START, NIGHT_END, 8);
        assertThat(straddle.recognizedMinutes()).isEqualTo(120);
        assertThat(straddle.basis()).isEqualTo("기준 근무시간과 겹친 시간 제외");

        // 07:00~19:00 로 넓게 잡힌 건 — 조출 2시간 + 야근 1시간
        Line both = OvertimePayCalculator.recognize(at(23, 7, 0), at(23, 19, 0), OvertimeDayType.WORKDAY,
                List.of(new Presence(at(23, 7, 0), at(23, 19, 0))), true, regular, 480, 480, NIGHT_START, NIGHT_END, 8);
        assertThat(both.recognizedMinutes()).isEqualTo(180);

        // 출퇴근 기록이 없는 운영이어도 정규 근무시간은 뺀다
        Line noRecord = OvertimePayCalculator.recognize(at(23, 17, 0), at(23, 20, 0), OvertimeDayType.WORKDAY,
                List.of(), false, regular, 480, 480, NIGHT_START, NIGHT_END, 8);
        assertThat(noRecord.recognizedMinutes()).isEqualTo(120);

        // 오후 반차(정규 09:00~14:00)를 쓴 날 저녁 19:00~21:00 은 그대로 인정
        Line halfDay = OvertimePayCalculator.recognize(at(23, 19, 0), at(23, 21, 0), OvertimeDayType.WORKDAY,
                List.of(new Presence(at(23, 19, 0), at(23, 21, 0))), true,
                new Presence(at(23, 9, 0), at(23, 14, 0)), 240, 480, NIGHT_START, NIGHT_END, 8);
        assertThat(halfDay.recognizedMinutes()).isEqualTo(120);
    }

    @Test
    void weekdayExtendedAtNightStacksBothRates() {
        // 평일 22:00~24:00 연장 2시간 → 2×1.5 + 2×0.5 = 4시간
        Line line = weekday(at(23, 22, 0), at(24, 0, 0),
                List.of(new Presence(at(23, 9, 0), at(24, 0, 0))), true, 480);

        assertThat(OvertimePayCalculator.weightedMinutes(line, OvertimeDayType.WORKDAY, setting()))
                .isEqualByComparingTo(BigDecimal.valueOf(240));
    }

    @Test
    void laborDayUsesItsOwnRate() {
        Line line = OvertimePayCalculator.recognize(at(23, 9, 0), at(23, 13, 0), OvertimeDayType.LABOR_DAY,
                List.of(), false, 0, 480, NIGHT_START, NIGHT_END, 8);

        // 4시간 × 2.5
        assertThat(OvertimePayCalculator.weightedMinutes(line, OvertimeDayType.LABOR_DAY, setting()))
                .isEqualByComparingTo(BigDecimal.valueOf(600));
    }

    @Test
    void regularWorkedMinutesClipToScheduleAndDropStatutoryBreak() {
        // 09:00~18:00 근무 → 점심 1시간 빼고 8시간
        assertThat(OvertimePayCalculator.regularWorkedMinutes(at(23, 8, 40), at(23, 20, 30), at(23, 9, 0), at(23, 18, 0)))
                .isEqualTo(480);
        // 10:00 출근(지각) → 자리에 8시간, 점심 1시간 빼고 7시간
        assertThat(OvertimePayCalculator.regularWorkedMinutes(at(23, 10, 0), at(23, 18, 0), at(23, 9, 0), at(23, 18, 0)))
                .isEqualTo(420);
        // 14:00 조퇴 → 자리에 5시간, 30분 빼고 4시간 30분
        assertThat(OvertimePayCalculator.regularWorkedMinutes(at(23, 9, 0), at(23, 14, 0), at(23, 9, 0), at(23, 18, 0)))
                .isEqualTo(270);
        // 오후 반차(09:00~13:00) → 4시간
        assertThat(OvertimePayCalculator.regularWorkedMinutes(at(23, 9, 0), at(23, 13, 0), at(23, 9, 0), at(23, 18, 0)))
                .isEqualTo(240);
        assertThat(OvertimePayCalculator.regularWorkedMinutes(null, at(23, 13, 0), at(23, 9, 0), at(23, 18, 0))).isZero();
    }

    @Test
    void hourlyWageComesFromTheDailyLeaveAllowance() {
        // 연차수당 1일 120,000원 ÷ 8시간 = 15,000원
        BigDecimal hourly = OvertimePayCalculator.hourlyWage(new BigDecimal("120000"), 8);
        assertThat(hourly).isEqualByComparingTo("15000");
        // 환산 4시간 → 60,000원
        assertThat(OvertimePayCalculator.amount(hourly, BigDecimal.valueOf(240))).isEqualByComparingTo("60000");
        // 단가 미설정은 0원이 아니라 '없음'
        assertThat(OvertimePayCalculator.hourlyWage(null, 8)).isNull();
        assertThat(OvertimePayCalculator.amount(null, BigDecimal.valueOf(240))).isNull();
    }
}
