package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TeamWeeklyStatus {
    private Long id;
    private Long teamId;
    private String weekKey;
    private Boolean submitted;
    private LocalDateTime submittedAt;
}
