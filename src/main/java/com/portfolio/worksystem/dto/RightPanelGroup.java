package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.EmployeePresence;
import java.util.List;

public record RightPanelGroup(
        String name,
        List<RightPanelTeam> teams
) {
    public record RightPanelTeam(
            Long teamId,
            String name,
            List<EmployeePresence> users
    ) {
    }
}
