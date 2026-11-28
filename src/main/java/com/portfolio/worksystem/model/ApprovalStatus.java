package com.portfolio.worksystem.model;

public enum ApprovalStatus {
    PENDING("승인 대기"),
    APPROVED("승인"),
    REJECTED("반려");

    private final String label;

    ApprovalStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
