package com.portfolio.worksystem.model;

public enum BusinessTripStatus {
    PENDING("승인 대기"),
    APPROVED("승인"),
    REJECTED("반려"),
    CANCELED("취소");

    private final String label;

    BusinessTripStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}