package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** [PAY] 직급별 통상시급 — '이날부터 이 금액'(V92). */
@Data
public class GradeHourlyWage {
    private Long id;
    private Long gradeId;
    private String gradeName;
    private LocalDate effectiveFrom;
    private BigDecimal hourlyWage;
    private String note;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
}
