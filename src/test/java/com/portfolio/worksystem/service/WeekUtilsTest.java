package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WeekUtilsTest {
    @Test
    void createsIsoWeekKeyAndPeriod() {
        String weekKey = WeekUtils.getWeekKey(LocalDate.of(2026, 4, 3));

        assertThat(weekKey).isEqualTo("2026-W14");
        assertThat(WeekUtils.getWeekStart(weekKey)).isEqualTo(LocalDate.of(2026, 3, 30));
        assertThat(WeekUtils.getWeekEnd(weekKey)).isEqualTo(LocalDate.of(2026, 4, 3));
        // [UX7] 한국식 월 주차 — 3/30(월) 주의 목요일 4/2가 4월 첫 목요일 → 4월 1주차
        assertThat(WeekUtils.getWeekLabel(weekKey)).isEqualTo("2026년 4월 1주차");
        assertThat(WeekUtils.getWeekPeriod(weekKey)).isEqualTo("03/30 ~ 04/03");
    }

    @Test
    void monthWeekLabelFollowsThursdayRule() {
        // 2026-07-03(금) 주: 6/29(월)~, 목=7/2 → 7월 1주차
        assertThat(WeekUtils.getWeekLabel(WeekUtils.getWeekKey(LocalDate.of(2026, 7, 3)))).isEqualTo("2026년 7월 1주차");
        // 2026-01-01(목) 주: 목=1/1 → 1월 1주차
        assertThat(WeekUtils.getWeekLabel(WeekUtils.getWeekKey(LocalDate.of(2026, 1, 1)))).isEqualTo("2026년 1월 1주차");
        // 2025-01-01(수) 주: 목=1/2 → 1월 1주차, 그 전 주(12/26 목) → 2024년 12월 4주차
        assertThat(WeekUtils.getWeekLabel(WeekUtils.getWeekKey(LocalDate.of(2025, 1, 1)))).isEqualTo("2025년 1월 1주차");
        assertThat(WeekUtils.getWeekLabel(WeekUtils.getWeekKey(LocalDate.of(2024, 12, 29)))).isEqualTo("2024년 12월 4주차");
        // 2026-05-01(금) 주: 목=4/30 → 4월 5주차 (5/1이라도 목요일 기준으로 전월)
        assertThat(WeekUtils.getWeekLabel(WeekUtils.getWeekKey(LocalDate.of(2026, 5, 1)))).isEqualTo("2026년 4월 5주차");
    }

    @Test
    void shiftsWeekAcrossYearBoundary() {
        assertThat(WeekUtils.shiftWeek("2026-W01", -1)).isEqualTo("2025-W52");
        assertThat(WeekUtils.shiftWeek("2026-W52", 1)).isEqualTo("2026-W53");
    }

    @Test
    void movesWeekendDueDateToNextMonday() {
        assertThat(WeekUtils.enforceWeekday(LocalDate.of(2026, 4, 4))).isEqualTo(LocalDate.of(2026, 4, 6));
        assertThat(WeekUtils.enforceWeekday(LocalDate.of(2026, 4, 5))).isEqualTo(LocalDate.of(2026, 4, 6));
        assertThat(WeekUtils.enforceWeekday(LocalDate.of(2026, 4, 6))).isEqualTo(LocalDate.of(2026, 4, 6));
    }
}