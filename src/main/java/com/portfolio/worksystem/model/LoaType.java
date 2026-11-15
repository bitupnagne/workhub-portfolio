package com.portfolio.worksystem.model;

import lombok.Data;

/** [B7] 휴직 종류 정의(loa_types, V84) — 사유마다 연차 출근율 반영 방식이 다르다. */
@Data
public class LoaType {
    private String code;
    private String label;
    /** {@link LoaAccrualTreatment} 코드. */
    private String accrualTreatment;
    private Boolean evidenceRequired;
    private Boolean active;
    private Integer sortOrder;

    public LoaAccrualTreatment treatment() {
        return LoaAccrualTreatment.fromCode(accrualTreatment);
    }

    public String getTreatmentLabel() {
        LoaAccrualTreatment t = treatment();
        return t == null ? accrualTreatment : t.getLabel();
    }

    /** 이 휴직이 연차를 깎는지 — 화면 안내용. */
    public boolean reducesEntitlement() {
        return treatment() == LoaAccrualTreatment.EXCLUDED;
    }
}
