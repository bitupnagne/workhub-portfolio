package com.portfolio.worksystem.model;

public enum RequesterLevel {
    MEMBER("팀원"),
    TEAM_LEADER("팀장"),
    DIVISION_LEADER("본부장"),
    CEO("대표이사");

    private final String label;

    RequesterLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
