package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class LeaveBalance {
    private Long id;
    private Long userId;
    private Integer balanceYear;
    private BigDecimal totalDays;
    private BigDecimal usedDays;
    private BigDecimal adjustedDays;
    private String note;

    public BigDecimal getRemainingDays() {
        BigDecimal total = totalDays == null ? BigDecimal.ZERO : totalDays;
        BigDecimal adjusted = adjustedDays == null ? BigDecimal.ZERO : adjustedDays;
        BigDecimal used = usedDays == null ? BigDecimal.ZERO : usedDays;
        return total.add(adjusted).subtract(used);
    }
}