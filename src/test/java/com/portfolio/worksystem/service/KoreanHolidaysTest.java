package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** [A6] 실제 관보 기준 2025·2026년 공휴일로 대체공휴일 규칙 검증. */
class KoreanHolidaysTest {

    private List<LocalDate> dates(int year) {
        return KoreanHolidays.withSubstitutes(year).stream()
                .map(KoreanHolidays.Holiday::date)
                .collect(Collectors.toList());
    }

    @Test
    void year2025MatchesOfficialCalendar() {
        List<LocalDate> dates = dates(2025);
        // 삼일절(토) → 3/3(월) 대체
        assertThat(dates).contains(LocalDate.of(2025, 3, 3));
        // 어린이날=부처님오신날(5/5 월 겹침) → 5/6(화) 대체
        assertThat(dates).contains(LocalDate.of(2025, 5, 6));
        // 추석 연휴 10/5(일)~10/7 → 10/8(수) 대체
        assertThat(dates).contains(LocalDate.of(2025, 10, 5));
        assertThat(dates).contains(LocalDate.of(2025, 10, 8));
        // 설날 연휴 1/28~1/30(화~목) — 대체 없음
        assertThat(dates).contains(LocalDate.of(2025, 1, 28), LocalDate.of(2025, 1, 29), LocalDate.of(2025, 1, 30));
        assertThat(dates).doesNotContain(LocalDate.of(2025, 1, 31));
        // 5/5는 한 번만(중복 병합)
        assertThat(dates.stream().filter(d -> d.equals(LocalDate.of(2025, 5, 5))).count()).isEqualTo(1);
    }

    @Test
    void year2026MatchesOfficialCalendar() {
        List<LocalDate> dates = dates(2026);
        // 삼일절(일) → 3/2(월) 대체
        assertThat(dates).contains(LocalDate.of(2026, 3, 2));
        // 부처님오신날 5/24(일) → 5/25(월) 대체
        assertThat(dates).contains(LocalDate.of(2026, 5, 25));
        // 광복절 8/15(토) → 8/17(월) 대체
        assertThat(dates).contains(LocalDate.of(2026, 8, 17));
        // 개천절 10/3(토) → 10/5(월) 대체
        assertThat(dates).contains(LocalDate.of(2026, 10, 5));
        // 추석 연휴 9/24~26(목~토) — 설·추석은 일요일 겹침만 대체 → 대체 없음
        assertThat(dates).contains(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26));
        assertThat(dates).doesNotContain(LocalDate.of(2026, 9, 28));
        // 현충일 6/6(토) — 대체 없음
        assertThat(dates).doesNotContain(LocalDate.of(2026, 6, 8));
    }

    @Test
    void unsupportedYearThrows() {
        assertThatThrownBy(() -> KoreanHolidays.forYear(2031)).isInstanceOf(IllegalArgumentException.class);
        assertThat(KoreanHolidays.supports(2030)).isTrue();
        assertThat(KoreanHolidays.supports(2019)).isFalse();
    }
}
