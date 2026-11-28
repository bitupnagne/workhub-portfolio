package com.portfolio.worksystem.model;

/** [B7] 휴직 신청 상태. */
public enum LoaStatus {
    PENDING("승인 대기"),
    APPROVED("승인"),
    REJECTED("반려"),
    CANCELED("취소");

    private final String label;

    LoaStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
