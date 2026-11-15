package com.portfolio.worksystem.model;

import java.time.LocalDate;
import lombok.Data;

@Data
public class ReportItemDetail {
    private Long id;
    private Long reportItemId;
    private String text;
    private LocalDate dueDate;
    private String cycle;
    private Integer displayOrder;
}
