package com.portfolio.worksystem.model;

public enum ProjectTaskStatus {
    TODO("예정"), IN_PROGRESS("진행 중"), REVIEW("검토"), BLOCKED("보류"), DONE("완료");

    private final String label;
    ProjectTaskStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
