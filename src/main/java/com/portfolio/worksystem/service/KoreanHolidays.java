package com.portfolio.worksystem.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [A6] 한국 법정공휴일 내장 데이터(폐쇄망 대응) — 양력 고정일 + 음력 명절(설/추석/부처님오신날)
 * 양력 환산 상수 + 대체공휴일 규칙. 음력 상수는 검증된 2020~2030년만 동봉 —
 * 그 외 연도(임시공휴일 포함)는 CSV 반입 기능을 사용한다.
 */
public final class KoreanHolidays {

    /** 대체공휴일 규칙: NONE=없음(신정·현충일), WEEKEND=토·일 겹침 시, SUNDAY_ONLY=일요일 겹침 시(설·추석 연휴). */
    public enum SubstituteRule { NONE, WEEKEND, SUNDAY_ONLY }

    public record Holiday(LocalDate date, String name, SubstituteRule rule) {}

    /** 설날(음 1/1)의 양력일 */
    private static final Map<Integer, LocalDate> SEOLLAL = Map.ofEntries(
            Map.entry(2020, LocalDate.of(2020, 1, 25)),
            Map.entry(2021, LocalDate.of(2021, 2, 12)),
            Map.entry(2022, LocalDate.of(2022, 2, 1)),
            Map.entry(2023, LocalDate.of(2023, 1, 22)),
            Map.entry(2024, LocalDate.of(2024, 2, 10)),
            Map.entry(2025, LocalDate.of(2025, 1, 29)),
            Map.entry(2026, LocalDate.of(2026, 2, 17)),
            Map.entry(2027, LocalDate.of(2027, 2, 6)),
            Map.entry(2028, LocalDate.of(2028, 1, 26)),
            Map.entry(2029, LocalDate.of(2029, 2, 13)),
            Map.entry(2030, LocalDate.of(2030, 2, 3)));

    /** 부처님오신날(음 4/8)의 양력일 */
    private static final Map<Integer, LocalDate> BUDDHA = Map.ofEntries(
            Map.entry(2020, LocalDate.of(2020, 4, 30)),
            Map.entry(2021, LocalDate.of(2021, 5, 19)),
            Map.entry(2022, LocalDate.of(2022, 5, 8)),
            Map.entry(2023, LocalDate.of(2023, 5, 27)),
            Map.entry(2024, LocalDate.of(2024, 5, 15)),
            Map.entry(2025, LocalDate.of(2025, 5, 5)),
            Map.entry(2026, LocalDate.of(2026, 5, 24)),
            Map.entry(2027, LocalDate.of(2027, 5, 13)),
            Map.entry(2028, LocalDate.of(2028, 5, 2)),
            Map.entry(2029, LocalDate.of(2029, 5, 20)),
            Map.entry(2030, LocalDate.of(2030, 5, 9)));

    /** 추석(음 8/15)의 양력일 */
    private static final Map<Integer, LocalDate> CHUSEOK = Map.ofEntries(
            Map.entry(2020, LocalDate.of(2020, 10, 1)),
            Map.entry(2021, LocalDate.of(2021, 9, 21)),
            Map.entry(2022, LocalDate.of(2022, 9, 10)),
            Map.entry(2023, LocalDate.of(2023, 9, 29)),
            Map.entry(2024, LocalDate.of(2024, 9, 17)),
            Map.entry(2025, LocalDate.of(2025, 10, 6)),
            Map.entry(2026, LocalDate.of(2026, 9, 25)),
            Map.entry(2027, LocalDate.of(2027, 9, 15)),
            Map.entry(2028, LocalDate.of(2028, 10, 3)),
            Map.entry(2029, LocalDate.of(2029, 9, 22)),
            Map.entry(2030, LocalDate.of(2030, 9, 12)));

    private KoreanHolidays() {
    }

    public static boolean supports(int year) {
        return SEOLLAL.containsKey(year);
    }

    public static String supportedRange() {
        return "2020~2030";
    }

    /** 기본 공휴일(대체 미적용). */
    public static List<Holiday> forYear(int year) {
        if (!supports(year)) {
            throw new IllegalArgumentException("내장 공휴일 데이터 범위(" + supportedRange() + ") 밖의 연도: " + year);
        }
        List<Holiday> list = new ArrayList<>();
        list.add(new Holiday(LocalDate.of(year, 1, 1), "신정", SubstituteRule.NONE));
        LocalDate seol = SEOLLAL.get(year);
        list.add(new Holiday(seol.minusDays(1), "설날 연휴", SubstituteRule.SUNDAY_ONLY));
        list.add(new Holiday(seol, "설날", SubstituteRule.SUNDAY_ONLY));
        list.add(new Holiday(seol.plusDays(1), "설날 연휴", SubstituteRule.SUNDAY_ONLY));
        list.add(new Holiday(LocalDate.of(year, 3, 1), "삼일절", SubstituteRule.WEEKEND));
        list.add(new Holiday(LocalDate.of(year, 5, 5), "어린이날", SubstituteRule.WEEKEND));
        list.add(new Holiday(BUDDHA.get(year), "부처님오신날", SubstituteRule.WEEKEND));
        list.add(new Holiday(LocalDate.of(year, 6, 6), "현충일", SubstituteRule.NONE));
        list.add(new Holiday(LocalDate.of(year, 8, 15), "광복절", SubstituteRule.WEEKEND));
        LocalDate chuseok = CHUSEOK.get(year);
        list.add(new Holiday(chuseok.minusDays(1), "추석 연휴", SubstituteRule.SUNDAY_ONLY));
        list.add(new Holiday(chuseok, "추석", SubstituteRule.SUNDAY_ONLY));
        list.add(new Holiday(chuseok.plusDays(1), "추석 연휴", SubstituteRule.SUNDAY_ONLY));
        list.add(new Holiday(LocalDate.of(year, 10, 3), "개천절", SubstituteRule.WEEKEND));
        list.add(new Holiday(LocalDate.of(year, 10, 9), "한글날", SubstituteRule.WEEKEND));
        list.add(new Holiday(LocalDate.of(year, 12, 25), "성탄절", SubstituteRule.WEEKEND));
        list.sort(java.util.Comparator.comparing(Holiday::date));
        return list;
    }

    /**
     * 대체공휴일 포함 목록. 규칙(2023.5 확대 기준):
     * 설·추석 연휴는 일요일 겹침 시, 그 외 대상 공휴일은 토·일 겹침 시,
     * 그리고 다른 공휴일과 같은 날(예: 2025 어린이날=부처님오신날)이면
     * 이후 첫 비휴일·평일이 대체공휴일. 신정·현충일은 대체 없음.
     */
    public static List<Holiday> withSubstitutes(int year) {
        List<Holiday> base = forYear(year);
        // 대체일 탐색 전에 기본 공휴일 전부를 선점 — 설연휴 일요일 겹침 시 대체일이 연휴 뒤로 밀리도록.
        Set<LocalDate> occupied = new HashSet<>();
        for (Holiday h : base) {
            occupied.add(h.date());
        }
        Set<LocalDate> seen = new HashSet<>();
        List<Holiday> result = new ArrayList<>();
        for (Holiday h : base) {
            boolean duplicate = !seen.add(h.date());
            if (!duplicate) {
                result.add(h);
            }
            boolean weekend = h.date().getDayOfWeek() == DayOfWeek.SATURDAY
                    || h.date().getDayOfWeek() == DayOfWeek.SUNDAY;
            boolean needsSubstitute = switch (h.rule()) {
                case NONE -> false;
                case WEEKEND -> weekend || duplicate;
                case SUNDAY_ONLY -> h.date().getDayOfWeek() == DayOfWeek.SUNDAY || duplicate;
            };
            if (!needsSubstitute) {
                continue;
            }
            LocalDate cursor = h.date().plusDays(1);
            while (occupied.contains(cursor)
                    || cursor.getDayOfWeek() == DayOfWeek.SATURDAY
                    || cursor.getDayOfWeek() == DayOfWeek.SUNDAY) {
                cursor = cursor.plusDays(1);
            }
            occupied.add(cursor);
            result.add(new Holiday(cursor, "대체공휴일(" + h.name() + ")", SubstituteRule.NONE));
        }
        result.sort(java.util.Comparator.comparing(Holiday::date));
        return result;
    }
}
