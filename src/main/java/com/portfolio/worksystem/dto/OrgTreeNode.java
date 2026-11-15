package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.EmployeePresence;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class OrgTreeNode {
    private Long teamId;
    private String name;
    private String nodeType;
    private String leaderName;
    private String leaderRole;
    private List<EmployeePresence> members = new ArrayList<>();
    private List<OrgTreeNode> children = new ArrayList<>();
}
