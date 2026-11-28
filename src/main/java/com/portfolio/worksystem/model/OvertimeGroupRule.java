package com.portfolio.worksystem.model;

import lombok.Data;

/**
 * [OT] 조별 시간외 기준(overtime_group_rules).
 *
 * <p>{@code groupCode} 는 시차출근 조 코드이고 {@code DEFAULT} 는 조가 없는 기본 근무자다.
 * 행이 없는 조는 DEFAULT 를 따른다.
 */
@Data
public class OvertimeGroupRule {
    public static final String DEFAULT_GROUP = "DEFAULT";

    private String groupCode;
    /** 화면 표시용 — 조 이름. DEFAULT 는 '기본 근무'. */
    private String groupLabel;
    /** 하루 실근로가 이 분을 넘은 부분부터 연장으로 본다. */
    private Integer extendedAfterMinutes;
    /** 급여에 이미 포함된 월 시간외 시간(포괄임금, 분). */
    private Integer inclusiveMonthlyMinutes;
    /** 저장된 행이 있는지 — 없으면 DEFAULT 값을 물려받아 보여 주는 중이다. */
    private boolean configured;

    public double getExtendedAfterHours() {
        return extendedAfterMinutes == null ? 0 : extendedAfterMinutes / 60.0;
    }

    public double getInclusiveMonthlyHours() {
        return inclusiveMonthlyMinutes == null ? 0 : inclusiveMonthlyMinutes / 60.0;
    }
}
