package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

/** [D3] 연차 부여 기준 정책(leave_accrual_policy, V77) — 단일 행(id=1). */
@Data
public class LeaveAccrualPolicy {
    private Integer id;
    /** HIRE_DATE(입사일 기준) | FISCAL_YEAR(회계연도 기준) */
    private String basis;
    /** 이 연도부터 새 기준 적용 — 이전 연도는 기존(입사일 기준) 방식 유지 */
    private Integer effectiveFromYear;
    /**
     * 가산 포함 총 부여일수 상한(V81). null = 상한 없음.
     *
     * <p>근로기준법 제60조 4항의 25일 한도를 넣을 수 있게 한 것이다. 기본값을 두지 않은 이유는
     * 법정 최소를 웃도는 부여 자체는 회사가 정할 수 있는 일이라서다.
     */
    private Integer maxAnnualDays;
    private Long updatedBy;
    private String updatedByName;
    private LocalDateTime updatedAt;

    public boolean isFiscalYear() {
        return "FISCAL_YEAR".equals(basis);
    }

    public boolean appliesTo(int year) {
        return effectiveFromYear != null && year >= effectiveFromYear;
    }
}
