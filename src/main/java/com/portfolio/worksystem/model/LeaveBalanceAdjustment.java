package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** [A5] 연차 수동 조정 이력(leave_balance_adjustments, V73). */
@Data
public class LeaveBalanceAdjustment {
    private Long id;
    private Long userId;
    private String memberName;
    private String teamName;
    private Integer balanceYear;
    private BigDecimal deltaDays;
    private String reason;
    private Long adjustedBy;
    private String adjustedByName;
    private LocalDateTime createdAt;
}
