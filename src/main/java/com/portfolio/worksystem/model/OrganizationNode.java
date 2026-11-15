package com.portfolio.worksystem.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

@Getter
public class OrganizationNode {
    private final Team team;
    private final List<OrganizationNode> children = new ArrayList<>();

    public OrganizationNode(Team team) {
        this.team = team;
    }
}
