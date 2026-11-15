package com.portfolio.worksystem.model;

import lombok.Data;

/** 직원 정보와 연차수당에서 사용하는 직급·호봉 트리의 노드. */
@Data
public class EmpGrade {
    private Long id;
    private Long parentId;
    private String name;
    private Integer displayOrder;
    private Boolean active;
    private Integer userCount;
    /** 아래 필드는 조회된 트리를 순회하면서 계산한다. */
    private int depth;
    private String pathName;
    private int rankOrder;
    private boolean hasChildren;
    private int descendantUserCount;
}
