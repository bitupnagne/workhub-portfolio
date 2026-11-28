package com.portfolio.worksystem.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** [UX47] 출장보고서 작성 폼 — 경비는 '신청 시와 동일' 또는 '금액 변동'(변동 시 행 편집). */
@Data
public class TripReportForm {
    private String content;
    /** true = 금액 변동사항 존재(폼의 경비 행 사용), false = 신청 시와 동일(신청 경비 복사) */
    private boolean expenseChanged;
    private List<BusinessTripExpenseForm> expenses = new ArrayList<>();
}
