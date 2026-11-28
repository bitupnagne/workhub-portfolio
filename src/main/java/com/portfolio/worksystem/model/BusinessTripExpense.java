package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class BusinessTripExpense {
    private Long id;
    private Long tripId;
    private BusinessTripExpenseType expenseType;
    private String description;
    private BigDecimal amount;
    private String note;
    private Integer displayOrder;
    private LocalDateTime createdAt;
}
