package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Data;

@Data
public class FlexTimeRequest {
    private Long id;
    private Long userId;
    private String username;
    private String memberName;
    private Long teamId;
    private String teamName;
    /** shift_groups.code (V69부터 DB 관리) */
    private String shiftGroup;
    private String shiftGroupLabel;
    private LocalTime shiftStartTime;
    private LocalTime shiftEndTime;
    private LocalDate startDate;
    private LocalDate endDate;
    private String reason;
    private FlexTimeStatus status;
    private Integer currentApprovalStep;
    private Long approverId;
    private String approverName;
    private LocalDateTime approvedAt;
    private String rejectedReason;
    private LocalDateTime createdAt;

    public boolean isPending() {
        return status == FlexTimeStatus.PENDING;
    }

    /** 화면 표시용 조 이름 — 라벨이 없으면(삭제된 조 등) 코드로 폴백. */
    public String getShiftGroupDisplay() {
        if (shiftGroupLabel != null && !shiftGroupLabel.isBlank()) {
            return shiftGroupLabel;
        }
        return shiftGroup == null ? "-" : shiftGroup + "조";
    }

    /** 근무시간 판정용 조 정의로 변환(라벨·시각은 JOIN으로 채워진 값 사용). */
    public ShiftGroupDef toShiftGroupDef() {
        if (shiftGroup == null) {
            return null;
        }
        ShiftGroupDef def = new ShiftGroupDef();
        def.setCode(shiftGroup);
        def.setLabel(getShiftGroupDisplay());
        def.setStartTime(shiftStartTime);
        def.setEndTime(shiftEndTime);
        return def;
    }
}
