package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WeeklyReport {
    private Long id;
    private Long userId;
    private String weekKey;
    private Boolean submitted;
    private LocalDateTime submittedAt;
}
