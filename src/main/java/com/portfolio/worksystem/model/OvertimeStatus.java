package com.portfolio.worksystem.model;

/** [OT] 시간외 근무 신청 상태. */
public enum OvertimeStatus {
    PENDING("승인 대기"),
    APPROVED("승인"),
    REJECTED("반려"),
    CANCELED("취소");

    private final String label;

    OvertimeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
