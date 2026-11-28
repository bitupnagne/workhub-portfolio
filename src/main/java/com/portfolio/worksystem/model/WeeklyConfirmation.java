package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WeeklyConfirmation {
    private Long id;
    private String weekKey;
    private Boolean confirmed;
    private LocalDateTime confirmedAt;
    private Long confirmedBy;
}
