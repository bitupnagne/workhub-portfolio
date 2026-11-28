package com.portfolio.worksystem.model;

public enum AttendanceStatus {
    SCHEDULED("미출근", ""),
    NORMAL("정상", "정"),
    LATE("지각", "지"),
    EARLY_LEAVE("조퇴", "조"),
    ABSENT("결근", "결"),
    REMOTE("재택", "재"),
    OUTSIDE("외근", "외"),
    BUSINESS_TRIP("출장", "출"),
    HOLIDAY("휴무", "휴");

    private final String label;
    /** [A3] 근태 매트릭스 셀 축약 표기 */
    private final String shortLabel;

    AttendanceStatus(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public String getLabel() {
        return label;
    }

    public String getShortLabel() {
        return shortLabel;
    }
}
