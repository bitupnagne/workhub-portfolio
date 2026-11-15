package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class OrganizationChartController {
    private final OrganizationService organizationService;

    @GetMapping("/org")
    public String orgChart(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", "회사 조직도");
        model.addAttribute("active", "orgChart");
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
        model.addAttribute("organizationTree", organizationService.findOrganizationTree());
        model.addAttribute("organizationMembers", organizationService.findOrganizationChartMembers());
        model.addAttribute("organizationNodes", organizationService.organizationNodesForManagement());
        return "org-chart";
    }
}
