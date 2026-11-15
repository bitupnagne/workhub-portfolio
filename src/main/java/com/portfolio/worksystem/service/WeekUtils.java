package com.portfolio.worksystem.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.regex.Pattern;

public final class WeekUtils {
    private static final Pattern WEEK_KEY = Pattern.compile("\\d{4}-W\\d{2}");
    private static final WeekFields ISO = WeekFields.ISO;
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("MM/dd");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private WeekUtils() {
    }

    public static String getWeekKey(LocalDate date) {
        int week = date.get(ISO.weekOfWeekBasedYear());
        int year = date.get(ISO.weekBasedYear());
        return "%d-W%02d".formatted(year, week);
    }

    public static LocalDate getWeekStart(String weekKey) {
        validateWeekKey(weekKey);
        int year = Integer.parseInt(weekKey.substring(0, 4));
        int week = Integer.parseInt(weekKey.substring(6, 8));
        return LocalDate.of(year, 1, 4)
                .with(ISO.weekBasedYear(), year)
                .with(ISO.weekOfWeekBasedYear(), week)
                .with(ISO.dayOfWeek(), 1);
    }

    public static LocalDate getWeekEnd(String weekKey) {
        return getWeekStart(weekKey).plusDays(4);
    }

    public static String shiftWeek(String weekKey, int amount) {
        return getWeekKey(getWeekStart(weekKey).plusWeeks(amount));
    }

    /**
     * [UX7] 'yyyy년 m월 n주차' — 한국식(KS X ISO 8601 준용) 월 주차:
     * 주 시작=월요일, 그 주의 목요일이 속한 달이 그 주의 달이고 n=그 달의 몇 번째 목요일인지.
     * (예: 7/1이 금요일이면 그 주는 6월 마지막 주로 표기)
     */
    public static String getWeekLabel(String weekKey) {
        validateWeekKey(weekKey);
        java.time.LocalDate thursday = getWeekStart(weekKey).plusDays(3);
        int nth = (thursday.getDayOfMonth() - 1) / 7 + 1;
        return "%d년 %d월 %d주차".formatted(thursday.getYear(), thursday.getMonthValue(), nth);
    }

    public static String getWeekPeriod(String weekKey) {
        return "%s ~ %s".formatted(PERIOD.format(getWeekStart(weekKey)), PERIOD.format(getWeekEnd(weekKey)));
    }

    public static String getCurrentWeekKey() {
        return getWeekKey(LocalDate.now(SEOUL));
    }

    public static LocalDate enforceWeekday(LocalDate date) {
        if (date == null) {
            return null;
        }
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return date.plusDays(2);
        }
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return date.plusDays(1);
        }
        return date;
    }

    public static boolean isValidWeekKey(String weekKey) {
        return weekKey != null && WEEK_KEY.matcher(weekKey).matches();
    }

    public static void validateWeekKey(String weekKey) {
        if (!isValidWeekKey(weekKey)) {
            throw new IllegalArgumentException("올바르지 않은 주차 형식입니다: " + weekKey);
        }
    }
}