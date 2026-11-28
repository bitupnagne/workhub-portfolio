package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import lombok.Data;

/** [C2] 휴가 종류 정의(leave_types, V76) — code는 LeaveType enum 이름과 1:1. */
@Data
public class LeaveTypeDef {
    private String code;
    private String label;
    /** 1.0 전일 / 0.5 반차 */
    private BigDecimal dayWeight;
    /** 연차 잔여 차감 여부 */
    private Boolean deductAnnual;
    private Boolean evidenceRequired;
    private Boolean active;
    private Integer sortOrder;
    /** [UX11] 폼 전용 — '+ 행 추가'로 들어온 신규 행 표시(DB 컬럼 아님) */
    private Boolean isNew;

    public boolean isHalfDay() {
        return dayWeight != null && dayWeight.compareTo(BigDecimal.ONE) < 0;
    }
}
