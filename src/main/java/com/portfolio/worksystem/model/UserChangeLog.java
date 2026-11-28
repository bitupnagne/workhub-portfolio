package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * [B9] 인사정보 변경이력 한 줄(user_change_logs, V85).
 *
 * <p>필드 하나가 한 줄이다. 한 번의 저장에서 셋이 바뀌면 세 줄이 남고, 같은 시각으로 묶여
 * 화면에서 한 묶음으로 보인다.
 *
 * <p>{@code oldValue}/{@code newValue} 는 id 가 아니라 <b>그때의 표시값</b>이다 — 팀이
 * 사라지거나 이름이 바뀌어도 이력은 당시를 그대로 말해야 한다.
 */
@Data
public class UserChangeLog {
    private Long id;
    private Long userId;
    private String field;
    private String fieldLabel;
    private String oldValue;
    private String newValue;
    private String source;
    private Long changedBy;
    /** 조인해서 채우는 표시용 값. */
    private String changedByName;
    private LocalDateTime changedAt;

    public String getSourceLabel() {
        return switch (source == null ? "" : source) {
            case "SELF" -> "본인 수정";
            case "ADMIN" -> "관리자 (직원관리)";
            case "HR_CARD" -> "관리자 (인사카드)";
            case "TRANSFER" -> "인사이동";
            case "LOA" -> "휴직";
            case "SYSTEM" -> "시스템";
            default -> source;
        };
    }

    /** 값이 비어 있으면 화면에서 '-' 로 보이게. */
    public String getOldText() {
        return oldValue == null || oldValue.isBlank() ? "-" : oldValue;
    }

    public String getNewText() {
        return newValue == null || newValue.isBlank() ? "-" : newValue;
    }
}
