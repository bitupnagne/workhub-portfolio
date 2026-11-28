package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 인사이동 1건(연도별 이력). 발령일 기준 팀 변경. */
@Data
public class PersonnelTransfer {
    private Long id;
    private Long userId;
    private Long fromTeamId;
    private Long toTeamId;
    private LocalDate effectiveDate;
    private Integer transferYear;
    private boolean applied;
    private LocalDateTime appliedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // 표시용 조인
    private String userName;
    private String fromTeamName;
    private String toTeamName;
    private String createdByName;
}
