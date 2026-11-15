package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.BusinessTripExpenseType;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class BusinessTripExpenseForm {
    private BusinessTripExpenseType expenseType;
    private String description;
    private BigDecimal amount;
    private String note;
}
