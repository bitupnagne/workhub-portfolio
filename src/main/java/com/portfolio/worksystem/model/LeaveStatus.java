package com.portfolio.worksystem.model;

public enum LeaveStatus {
    PENDING("승인 대기"),
    APPROVED("승인"),
    REJECTED("반려"),
    CANCELED("취소"),
    CANCEL_PENDING("취소 결재 진행"),
    CANCEL_APPROVED("취소 승인");

    private final String label;

    LeaveStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}