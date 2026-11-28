package com.portfolio.worksystem.model;

public enum ItemStatus {
    NEW("신규"),
    CONTINUE("계속"),
    DONE("완료");

    private final String label;

    ItemStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
