package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import java.time.LocalTime;
import lombok.Data;

/**
 * [OT] 시간외 근무 전사 설정(overtime_settings, 1행).
 *
 * <p>가산율은 전부 <b>통상시급 × N</b>의 N 이다. 야간만 다른 구분에 <b>겹쳐 붙는 추가분</b>이다 —
 * 연장 1.5 + 야간 0.5 = 2.0.
 */
@Data
public class OvertimeSetting {
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.TIME)
    private LocalTime nightStart;
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.TIME)
    private LocalTime nightEnd;
    private BigDecimal rateExtended;
    private BigDecimal rateNight;
    private BigDecimal rateHoliday;
    private BigDecimal rateHolidayOver;
    private BigDecimal ratePublicHoliday;
    private BigDecimal rateLaborDay;
    private Integer holidayOverHours;
    private Integer weeklyLimitHours;
    private Integer dailyStandardHours;
    /** 기준 근무시간을 이만큼(분) 넘겨 일한 날부터 '신청하지 않은 시간외 근무'로 안내한다. */
    private Integer noticeThresholdMinutes;
    private Long updatedBy;

    /** 근무일 성격에 맞는 기본 가산율(휴일 초과분·야간 추가분은 따로 붙는다). */
    public BigDecimal baseRate(OvertimeDayType dayType) {
        return switch (dayType) {
            case WORKDAY -> rateExtended;
            case HOLIDAY -> rateHoliday;
            case PUBLIC_HOLIDAY -> ratePublicHoliday;
            case LABOR_DAY -> rateLaborDay;
        };
    }
}
