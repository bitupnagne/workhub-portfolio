package com.portfolio.worksystem.model;

/**
 * [B7] 휴직 기간을 연차 <b>출근율</b> 계산에서 어떻게 볼 것인가.
 *
 * <p>근로기준법 제60조는 1년간 소정근로일의 80% 이상 출근한 사람에게 연차를 준다.
 * 휴직 기간을 어느 쪽으로 세느냐에 따라 부여일수가 달라진다.
 */
public enum LoaAccrualTreatment {
    /**
     * 출근한 것으로 본다 — 연차가 깎이지 않는다.
     *
     * <p>육아휴직과 업무상 부상·질병이 여기 해당한다. 법이 출근으로 간주하도록 정하고 있다.
     */
    COUNTED_AS_WORK("출근 간주 (연차 영향 없음)"),

    /**
     * 소정근로일에서 빼고 <b>남은 기간에 비례해</b> 부여한다.
     *
     * <p>개인 사정 휴직 등 통상의 경우다. 1년 중 절반을 쉬었으면 그 해 연차도 절반이다.
     */
    EXCLUDED("소정근로일 제외 (비례 삭감)");

    private final String label;

    LoaAccrualTreatment(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static LoaAccrualTreatment fromCode(String code) {
        for (LoaAccrualTreatment t : values()) {
            if (t.name().equals(code)) {
                return t;
            }
        }
        return null;
    }
}
