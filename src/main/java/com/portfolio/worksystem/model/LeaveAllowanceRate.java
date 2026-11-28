package com.portfolio.worksystem.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * [D4] 연도·등급별 연차수당 1일 단가(leave_allowance_rates, V82).
 *
 * <p>미사용 연차는 이월하지 않고 수당으로 지급하며, 단가는 직급(등급)마다 다르다.
 * 연도가 키에 들어가는 이유는 임금이 매년 오르기 때문이다 — 단가를 덮어쓰면 이미 지급이
 * 끝난 과거 연도 정산 금액이 소급해서 바뀐다.
 */
@Data
public class LeaveAllowanceRate {
    private Long id;
    private Integer rateYear;
    private Long gradeId;
    /** 화면 표시용 — 등급명(조인). */
    private String gradeName;
    /** [D5] '3급 › 5호봉' 처럼 뿌리부터의 경로. */
    private String pathName;
    /** [D5] 트리 깊이 — 화면 들여쓰기용. */
    private int depth;
    /** 연차 1일당 금액(원). */
    private BigDecimal dailyAmount;
    private Long updatedBy;
    private String updatedByName;
    private LocalDateTime updatedAt;
}
