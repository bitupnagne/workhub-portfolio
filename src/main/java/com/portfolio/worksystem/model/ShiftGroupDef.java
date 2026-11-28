package com.portfolio.worksystem.model;

import java.time.LocalTime;
import lombok.Data;

/** 시차출근 조 정의 — shift_groups 테이블(V69). start/end NULL이면 기본 근무시간을 따른다. */
@Data
public class ShiftGroupDef {
    private Long id;
    private String code;
    private String label;
    private LocalTime startTime;
    private LocalTime endTime;
    private Boolean active;
    private Integer sortOrder;

    public boolean followsDefault() {
        return startTime == null || endTime == null;
    }

    public String getLabel() {
        return label == null || label.isBlank() ? code : label;
    }
}
