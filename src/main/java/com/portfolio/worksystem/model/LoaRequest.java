package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * [B7] 휴직 신청서(loa_requests, V84).
 *
 * <p>{@code endDate} 가 null 이면 <b>복직일 미정</b>이다. 승인되면 users 의
 * loa_start/loa_end/loa_type 에 반영되어 근태·휴가·출근체크가 곧바로 이 상태를 따른다.
 */
@Data
public class LoaRequest {
    private Long id;
    private Long userId;
    private String type;
    /** 조인해서 채우는 표시용 값. */
    private String typeLabel;
    private String accrualTreatment;
    private String memberName;
    private String teamName;
    private Long teamId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    private LoaStatus status;
    private Integer currentApprovalStep;
    private Long decidedBy;
    private String rejectedReason;
    private LocalDateTime createdAt;

    public boolean isPending() {
        return status == LoaStatus.PENDING;
    }

    public String getPeriodText() {
        return startDate + " ~ " + (endDate == null ? "복직일 미정" : endDate.toString());
    }
}
