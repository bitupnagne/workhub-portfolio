package com.portfolio.worksystem.model;

public enum BusinessTripExpenseType {
    TRANSPORT("교통비"),
    LODGING("숙박비"),
    MEAL("식비"),
    DAILY("일비"),
    ETC("기타");

    private final String label;

    BusinessTripExpenseType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
