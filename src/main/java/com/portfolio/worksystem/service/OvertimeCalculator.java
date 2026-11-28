package com.portfolio.worksystem.service;

import com.portfolio.worksystem.model.CompanyHoliday;
import com.portfolio.worksystem.model.OvertimeDayType;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;

/**
 * [OT] 시간외 근무 시간 계산 — DB 없이 시각만으로 답이 나오는 부분을 모아 둔다.
 *
 * <p>출퇴근 기록의 출처(웹·앱·외부 단말)와 무관하게 <b>시각 두 개</b>만 받는다. 단말 연동이 들어와도
 * 여기는 바뀌지 않아야 한다.
 */
public final class OvertimeCalculator {

    private OvertimeCalculator() {
    }

    /** 구간 길이(분). 끝이 시작보다 빠르거나 같으면 0. */
    public static int minutesBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return 0;
        }
        return (int) Duration.between(start, end).toMinutes();
    }

    /**
     * [start, end) 중 야간 시간대에 걸친 분.
     *
     * <p>야간 시간대는 보통 자정을 넘는다(22:00~06:00). 구간이 여러 날에 걸쳐도 날마다의 야간 창과
     * 겹치는 부분을 더한다. nightStart == nightEnd 면 야간 없음으로 본다.
     */
    public static int nightMinutes(LocalDateTime start, LocalDateTime end, LocalTime nightStart, LocalTime nightEnd) {
        if (start == null || end == null || !end.isAfter(start)
                || nightStart == null || nightEnd == null || nightStart.equals(nightEnd)) {
            return 0;
        }
        boolean wraps = nightEnd.isBefore(nightStart);
        int total = 0;
        // 전날 밤에 시작해 오늘 새벽까지 이어지는 창을 놓치지 않도록 하루 앞에서부터 본다.
        for (LocalDate day = start.toLocalDate().minusDays(1); !day.isAfter(end.toLocalDate()); day = day.plusDays(1)) {
            LocalDateTime windowStart = LocalDateTime.of(day, nightStart);
            LocalDateTime windowEnd = LocalDateTime.of(wraps ? day.plusDays(1) : day, nightEnd);
            LocalDateTime from = start.isAfter(windowStart) ? start : windowStart;
            LocalDateTime to = end.isBefore(windowEnd) ? end : windowEnd;
            if (to.isAfter(from)) {
                total += (int) Duration.between(from, to).toMinutes();
            }
        }
        return total;
    }

    /**
     * 근무일의 성격. 우선순위는 근로자의 날 → 공휴일 → 휴일(회사 휴무·휴무 요일) → 평일.
     *
     * @param holiday 그날 등록된 회사 휴무/공휴일(없으면 null)
     * @param offDay  요일별 근무시간표상 휴무 요일인지(특정일 근무 지정이 있으면 false)
     */
    public static OvertimeDayType dayType(LocalDate date, CompanyHoliday holiday, boolean offDay) {
        if (date != null && date.getMonth() == Month.MAY && date.getDayOfMonth() == 1) {
            return OvertimeDayType.LABOR_DAY;
        }
        if (holiday != null && holiday.isPublicHoliday()) {
            return OvertimeDayType.PUBLIC_HOLIDAY;
        }
        if (holiday != null || offDay) {
            return OvertimeDayType.HOLIDAY;
        }
        return OvertimeDayType.WORKDAY;
    }

    /**
     * 자리에 있었던 길이(분)에서 자동으로 빼는 휴게(분) — 신청자가 입력하지 않는다.
     *
     * <p>근로 4시간에 30분, 8시간에 1시간. 다만 <b>휴게는 그 근로를 채운 뒤에 쉰 것으로 본다</b>:
     * 4시간(8시간)을 채우면 바로 퇴근을 찍을 수 있고 그 시간은 전부 인정한다. 그 뒤에 더 머문 시간부터
     * 30분까지를 휴게로 가정한다. 휴게가 지나기를 기다렸다가 퇴근을 찍을 필요가 없다.
     * <pre>
     *  머문 시간      휴게          인정
     *  ~4:00          0            그대로
     *  4:00~4:30      넘은 만큼     4:00
     *  4:30~8:30      30분         -30분
     *  8:30~9:00      넘은 만큼     8:00
     *  9:00~          60분         -60분
     * </pre>
     */
    public static int autoBreakMinutes(int spanMinutes) {
        if (spanMinutes <= 240) {
            return 0;
        }
        if (spanMinutes < 270) {
            return spanMinutes - 240;
        }
        if (spanMinutes < 510) {
            return 30;
        }
        if (spanMinutes < 540) {
            return spanMinutes - 480;
        }
        return 60;
    }

    /**
     * 휴게를 빼고 남은 야간 분. 휴게가 야간·주간 어디에 있었는지는 알 수 없으므로
     * 전체 대비 비율로 나눠 뺀다(야간에만 쉬었다고 가정해 과하게 깎지 않는다).
     */
    public static int nightAfterBreak(int grossMinutes, int nightMinutes, int breakMinutes) {
        if (grossMinutes <= 0 || nightMinutes <= 0) {
            return 0;
        }
        if (breakMinutes <= 0) {
            return nightMinutes;
        }
        long deducted = Math.round((double) breakMinutes * nightMinutes / grossMinutes);
        return (int) Math.max(0, nightMinutes - deducted);
    }
}
