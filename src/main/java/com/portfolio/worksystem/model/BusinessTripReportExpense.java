package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import lombok.Data;

/** [UX47] 출장보고서 경비 — 보고 시점 복사본(신청 경비 business_trip_expenses는 불변 보존). */
@Data
public class BusinessTripReportExpense {
    private Long id;
    private Long reportId;
    private BusinessTripExpenseType expenseType;
    private String description;
    private BigDecimal amount;
    private String note;
    private Integer displayOrder;
}
