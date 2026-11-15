package com.portfolio.worksystem.model;

import lombok.Data;

@Data
public class MemberStatus {
    private Long userId;
    private String name;
    private String dept;
    private Boolean submitted;
}
