package com.portfolio.worksystem.service;

import com.portfolio.worksystem.model.OvertimeDayType;
import com.portfolio.worksystem.model.OvertimeSetting;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * [OT] 인정 시간과 수당 계산 — DB 없이 숫자만으로 답이 나오는 부분.
 *
 * <p><b>인정 시간</b>은 승인된 신청 시간과 실제 출퇴근 기록이 겹치는 만큼이다. 신청만 하고 일하지 않은 시간도,
 * 신청 없이 더 일한 시간도 인정하지 않는다. 휴게는 입력받지 않고 머문 길이로 자동 차감한다
 * ({@link OvertimeCalculator#autoBreakMinutes}). 다만 그날 출퇴근 기록이 <b>하나도 없으면</b> 신청 시간을 그대로 인정한다 —
 * 출퇴근을 이 시스템에서 찍지 않는 운영(외부 출입 단말 연동 전 등)에서는 기록이 없는 것이 정상이기 때문이다.
 *
 * <p><b>가산</b>은 전부 '통상시급 × N'이다.
 * <ul>
 *   <li>평일: 그날 실근로가 조별 연장 기준을 넘은 부분만 연장(N=연장), 그 전까지는 기준 내(N=1).</li>
 *   <li>휴일·공휴일·근로자의 날: 기준 시간(기본 8시간)까지 그날 가산율, 넘은 부분은 휴일 초과 가산율.</li>
 *   <li>야간: 위 어느 구분이든 야간 시간대에 걸친 분에 야간 추가분을 더한다.</li>
 * </ul>
 */
public final class OvertimePayCalculator {

    private OvertimePayCalculator() {
    }

    /** 실제로 자리에 있었던 구간(정규 출퇴근 또는 시간외 기록). */
    public record Presence(LocalDateTime from, LocalDateTime to) {
    }

    /** 한 건의 인정 결과(분). */
    public record Line(int recognizedMinutes, int withinStandardMinutes, int extendedMinutes,
                       int holidayMinutes, int holidayOverMinutes, int nightMinutes, String basis) {

        public static Line empty(String basis) {
            return new Line(0, 0, 0, 0, 0, 0, basis);
        }
    }

    /**
     * 신청 한 건의 인정 시간.
     *
     * @param presences            그날의 실제 출퇴근 구간(없으면 빈 목록)
     * @param anyRecordThatDay     그날 출퇴근 기록이 하나라도 있는지 — 없으면 신청 시간을 그대로 인정
     * @param regularWorkedMinutes 그날 정규 근무로 일한 분(평일의 연장 기준 판정용)
     * @param extendedAfterMinutes 조별 연장 기준(분)
     */
    public static Line recognize(LocalDateTime startAt, LocalDateTime endAt, OvertimeDayType dayType,
                                 List<Presence> presences, boolean anyRecordThatDay,
                                 int regularWorkedMinutes, int extendedAfterMinutes,
                                 LocalTime nightStart, LocalTime nightEnd, int holidayOverHours) {
        return recognize(startAt, endAt, dayType, presences, anyRecordThatDay, null,
                regularWorkedMinutes, extendedAfterMinutes, nightStart, nightEnd, holidayOverHours);
    }

    /**
     * @param regularWindow 그날의 정규 근무 구간(없으면 null) — 이 구간과 겹친 시간은 인정하지 않는다.
     *                      정규 근무시간은 이미 급여에 들어 있어, 시간외로 또 세면 두 번 지급된다.
     */
    public static Line recognize(LocalDateTime startAt, LocalDateTime endAt, OvertimeDayType dayType,
                                 List<Presence> presences, boolean anyRecordThatDay, Presence regularWindow,
                                 int regularWorkedMinutes, int extendedAfterMinutes,
                                 LocalTime nightStart, LocalTime nightEnd, int holidayOverHours) {
        int gross = OvertimeCalculator.minutesBetween(startAt, endAt);
        if (gross <= 0) {
            return Line.empty("신청 시간 없음");
        }
        // 인정 후보 구간: 기록이 없으면 신청 전체, 있으면 신청과 겹친 실제 구간.
        List<Presence> candidates = new java.util.ArrayList<>();
        if (!anyRecordThatDay) {
            candidates.add(new Presence(startAt, endAt));
        } else {
            for (Presence presence : presences) {
                LocalDateTime from = presence.from().isAfter(startAt) ? presence.from() : startAt;
                LocalDateTime to = presence.to().isBefore(endAt) ? presence.to() : endAt;
                if (to.isAfter(from)) {
                    candidates.add(new Presence(from, to));
                }
            }
            if (candidates.isEmpty()) {
                return Line.empty("신청 시간에 출퇴근 기록 없음");
            }
        }
        int before = 0;
        int overlap = 0;
        int nightRaw = 0;
        for (Presence candidate : candidates) {
            before += OvertimeCalculator.minutesBetween(candidate.from(), candidate.to());
            for (Presence piece : outside(candidate, regularWindow)) {
                overlap += OvertimeCalculator.minutesBetween(piece.from(), piece.to());
                nightRaw += OvertimeCalculator.nightMinutes(piece.from(), piece.to(), nightStart, nightEnd);
            }
        }
        boolean clippedByRegular = overlap < before;
        if (overlap <= 0) {
            return Line.empty("기준 근무시간과 겹쳐 인정 시간 없음");
        }
        String basis;
        if (clippedByRegular) {
            basis = "기준 근무시간과 겹친 시간 제외";
        } else if (!anyRecordThatDay) {
            basis = "출퇴근 기록 없음 — 신청 시간 인정";
        } else {
            basis = overlap >= gross ? "출퇴근 기록과 일치" : "기록과 겹친 시간만 인정";
        }
        // 휴게는 실제로 머문 길이로 자동 계산한다(기준 근로를 채우고 바로 퇴근하면 빼지 않는다).
        int restShare = OvertimeCalculator.autoBreakMinutes(overlap);
        int recognized = Math.max(0, overlap - restShare);
        int night = OvertimeCalculator.nightAfterBreak(overlap, Math.min(nightRaw, overlap), restShare);
        night = Math.min(night, recognized);

        if (dayType == OvertimeDayType.WORKDAY) {
            int room = Math.max(0, extendedAfterMinutes - Math.max(0, regularWorkedMinutes));
            int within = Math.min(recognized, room);
            return new Line(recognized, within, recognized - within, 0, 0, night, basis);
        }
        int base = Math.min(recognized, Math.max(0, holidayOverHours) * 60);
        return new Line(recognized, 0, 0, base, recognized - base, night, basis);
    }

    /** 구간에서 정규 근무 구간과 겹친 부분을 뺀 나머지(앞쪽·뒤쪽 최대 두 조각). */
    static List<Presence> outside(Presence interval, Presence window) {
        if (window == null || !interval.from().isBefore(window.to()) || !interval.to().isAfter(window.from())) {
            return List.of(interval);
        }
        List<Presence> pieces = new java.util.ArrayList<>();
        if (interval.from().isBefore(window.from())) {
            pieces.add(new Presence(interval.from(), window.from()));
        }
        if (interval.to().isAfter(window.to())) {
            pieces.add(new Presence(window.to(), interval.to()));
        }
        return pieces;
    }

    /**
     * 정규 근무로 일한 분 — 출퇴근을 기준 근무시간 안으로 자르고 휴게를 뺀다.
     * 점심시간을 따로 기록하지 않으므로 자리에 있었던 길이로 추정한다: 8시간 이상이면 1시간,
     * 4시간을 넘으면 30분. 정확히 4시간(반차)은 휴게 없이 본다.
     */
    public static int regularWorkedMinutes(LocalDateTime checkIn, LocalDateTime checkOut,
                                           LocalDateTime scheduledStart, LocalDateTime scheduledEnd) {
        if (checkIn == null || checkOut == null) {
            return 0;
        }
        LocalDateTime from = checkIn.isAfter(scheduledStart) ? checkIn : scheduledStart;
        LocalDateTime to = checkOut.isBefore(scheduledEnd) ? checkOut : scheduledEnd;
        int span = OvertimeCalculator.minutesBetween(from, to);
        if (span >= 8 * 60) {
            return span - 60;
        }
        if (span > 4 * 60) {
            return span - 30;
        }
        return span;
    }

    /** 가산율을 곱한 환산 분(= 통상시급을 곱하면 수당이 되는 분). */
    public static BigDecimal weightedMinutes(Line line, OvertimeDayType dayType, OvertimeSetting setting) {
        BigDecimal total = BigDecimal.valueOf(line.withinStandardMinutes());
        total = total.add(setting.getRateExtended().multiply(BigDecimal.valueOf(line.extendedMinutes())));
        if (dayType != OvertimeDayType.WORKDAY) {
            total = total.add(setting.baseRate(dayType).multiply(BigDecimal.valueOf(line.holidayMinutes())));
            total = total.add(setting.getRateHolidayOver().multiply(BigDecimal.valueOf(line.holidayOverMinutes())));
        }
        return total.add(setting.getRateNight().multiply(BigDecimal.valueOf(line.nightMinutes())));
    }

    /** 통상시급 = 연차수당 1일 단가 ÷ 기준 시간. 단가가 없으면 null(0원이 아니다 — 아직 안 정한 것이다). */
    public static BigDecimal hourlyWage(BigDecimal dailyAllowance, Integer dailyStandardHours) {
        if (dailyAllowance == null || dailyStandardHours == null || dailyStandardHours <= 0) {
            return null;
        }
        return dailyAllowance.divide(BigDecimal.valueOf(dailyStandardHours), 2, RoundingMode.HALF_UP);
    }

    /** 수당(원). 통상시급이 없으면 null. */
    public static BigDecimal amount(BigDecimal hourlyWage, BigDecimal weightedMinutes) {
        if (hourlyWage == null || weightedMinutes == null) {
            return null;
        }
        return hourlyWage.multiply(weightedMinutes).divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
    }
}
