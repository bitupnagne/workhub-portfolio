package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

/** [PERM] 관리자 메뉴 권한 한 건 — 사람(USER) 또는 조직장 자리(TEAM_LEADER). */
@Data
public class AdminMenuGrant {
    public static final String USER = "USER";
    public static final String TEAM_LEADER = "TEAM_LEADER";

    private Long id;
    private String menuKey;
    private String granteeType;
    private Long userId;
    private Long teamId;
    /* 표시용 — 사람 */
    private String userName;
    private String userTeamName;
    private String userJobTitle;
    private Boolean userActive;
    /* 표시용 — 조직장 자리 */
    private String teamName;
    private Long leaderUserId;
    private String leaderName;
    private Long grantedBy;
    private String grantedByName;
    private LocalDateTime createdAt;

    public boolean isLeaderSeat() {
        return TEAM_LEADER.equals(granteeType);
    }

    /** 칩에 보일 이름. */
    public String getLabel() {
        if (isLeaderSeat()) {
            return (teamName == null ? "삭제된 조직" : teamName) + " 조직장";
        }
        return userName == null ? "삭제된 사용자" : userName;
    }

    /** 칩 보조 문구 — 사람이면 소속, 자리면 지금 앉은 사람. */
    public String getDetail() {
        if (isLeaderSeat()) {
            return leaderName == null ? "공석" : "현재 " + leaderName;
        }
        return userTeamName == null ? "" : userTeamName;
    }
}
