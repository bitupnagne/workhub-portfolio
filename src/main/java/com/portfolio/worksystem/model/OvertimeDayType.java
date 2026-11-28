package com.portfolio.worksystem.model;

/** [OT] 시간외 근무일의 성격 — 가산율이 여기서 갈린다. 신청 시점에 판정해 문서에 고정한다. */
public enum OvertimeDayType {
    WORKDAY("평일"),
    HOLIDAY("휴일"),
    PUBLIC_HOLIDAY("공휴일"),
    LABOR_DAY("근로자의 날");

    private final String label;

    OvertimeDayType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isRestDay() {
        return this != WORKDAY;
    }
}
