package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ReportItem {
    private Long id;
    private Long weeklyReportId;
    private Long userId;
    private Long teamId;
    private String memberName;
    private String teamName;
    private String content;
    private LocalDate dueDate;
    private String cycle;
    private ItemStatus status;
    private Integer displayOrder;
    private LocalDateTime createdAt;
    private Long sourceProjectId;
    private Long sourceTaskId;
    private Long sourceTeamId;
    private String sourceProjectName;
    private Long sourceActivityId;
    private Integer projectProgress;
    private String projectSummary;
    private String projectNote;
    private List<ReportItemDetail> details = new ArrayList<>();

    public boolean isProjectLinked() { return sourceTaskId != null; }
}
