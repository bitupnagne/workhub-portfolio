package com.portfolio.worksystem.model;

import lombok.Data;

@Data
public class ProjectPerson {
    private Long userId;
    private String name;
    private Long teamId;
    private String teamName;
}
