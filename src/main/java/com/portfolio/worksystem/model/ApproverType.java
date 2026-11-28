package com.portfolio.worksystem.model;

public enum ApproverType {
    TEAM_LEADER("팀장"),
    DIVISION_LEADER("본부장"),
    ROOT_LEADER("대표이사"),
    ORG_LEADER("조직장"),
    USER("직접 지정"),
    SELF("본인 결재");

    private final String label;

    ApproverType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
