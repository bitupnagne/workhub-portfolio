package com.portfolio.worksystem.model;

import lombok.Data;

/**
 * [D5] 직급 노드 — '급' 아래 '호봉', 그 아래로도 계속 붙일 수 있는 트리다.
 */
@Data
public class EmpGrade {
    private Long id;
    /** 상위 노드. null 이면 최상위(급). */
    private Long parentId;
    private String name;
    /** [GRADE] 사용자가 넣은 값('3', '선임', 'A') — 숫자일 필요가 없다. 비어 있으면(옛 데이터) name 을 값으로 본다. */
    private String gradeValue;
    /** [GRADE] 이 노드만의 단위. null 이면 단계 기본 단위, 빈 문자열이면 단위 없음. */
    private String unitLabel;
    /** [GRADE] 서비스가 채움 — 실제로 붙은 단위(단계 기본 또는 이 노드 지정). */
    private String effectiveUnit;
    /** [GRADE] 서비스가 채움 — 단위를 이 노드에 직접 정했는지. */
    private boolean unitOverridden;
    /** 형제 사이의 순서. 전체 서열은 트리를 앞에서부터 훑어 매긴다({@link #rankOrder}). */
    private Integer displayOrder;
    private Boolean active;
    /** 이 등급으로 지정된 재직 직원 수(목록 화면 표시·삭제 가드용). */
    private Integer userCount;

    /* ── 아래는 DB 컬럼이 아니라 서비스가 트리를 훑으며 채우는 값 ── */

    /** 0 = 최상위. 화면 들여쓰기에 쓴다. */
    private int depth;
    /** '3급 › 5호봉' 처럼 뿌리부터의 경로. */
    private String pathName;
    /** 전체 서열(1부터). 트리 앞순회 순서 = 위에서 아래로 읽는 순서. */
    private int rankOrder;
    /** 하위 노드가 있는지 — 있으면 직원을 여기 대신 말단에 붙이는 게 보통이다. */
    private boolean hasChildren;
    /** 자손까지 합친 재직 직원 수(삭제 가드용). */
    private int descendantUserCount;
}
