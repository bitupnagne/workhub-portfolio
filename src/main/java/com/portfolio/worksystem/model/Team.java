package com.portfolio.worksystem.model;

import lombok.Data;

@Data
public class Team {
    private Long id;
    private String code;
    private String name;
    private Long parentId;
    private String parentName;
    private String nodeType;
    private Long leaderUserId;
    private String leaderName;
    private Integer displayOrder;
    private Integer level;
    private String path;
    private Boolean active;
    private Boolean weeklyReportTarget;
    private boolean deletable;
    private String deleteBlockReason;

    public boolean isAssignableTeam() {
        return "TEAM".equals(nodeType);
    }

    /** 직원 소속으로 지정 가능한 노드: 팀 또는 본부(대표이사 ROOT 제외). */
    public boolean isMembershipAssignable() {
        return "TEAM".equals(nodeType) || "DIVISION".equals(nodeType);
    }
}
