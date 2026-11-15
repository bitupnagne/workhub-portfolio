package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.config.AccessZone;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.HrService;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.SystemSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [UX49] 외부망 런처(홈) — 대시보드가 외부망 숨김일 때의 대체 화면.
 * 사이드메뉴 없이, 지금 이 사용자가 실제로 들어갈 수 있는 메뉴만
 * (외부망 노출 허용 ∩ 본인 권한) 큰 정사각 카드로 배치한다.
 */
@RequiredArgsConstructor
@Controller
public class LauncherController {

    private final SystemSettingsService systemSettingsService;
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;
    private final OrganizationService organizationService;
    private final HrService hrService;

    /** 런처 카드 한 장 — groupKey는 아이콘 선택에 사용. */
    public record LaunchCard(String groupKey, String groupLabel, String label, String url) {}

    /** [UX51] 그룹(큰 메뉴) 카드 — 카드 안에 세부 메뉴 링크를 나열. */
    public record LaunchGroup(String groupKey, String groupLabel, List<LaunchCard> items) {}

    @GetMapping("/launcher")
    public String launcher(@AuthenticationPrincipal CustomUserDetails user,
                           HttpServletRequest request, Model model) {
        if (!releaseFeatures.isDashboardEnabled()) return "redirect:" + releaseFeatures.getHomeUrl();
        boolean external = AccessZone.isExternal(request);
        // [UX50] 내부망 슈퍼관리자는 정책 미적용(잠금 방지) — 대시보드가 그 존에서 숨김일 때만 런처
        boolean policyExempt = !external && user.isSuperAdmin();
        if (policyExempt || !systemSettingsService.isDashboardHidden(external)) {
            return "redirect:" + releaseFeatures.getHomeUrl();
        }
        Set<String> hidden = systemSettingsService.hiddenMenusFor(external);
        boolean canManageOrg = organizationService.canManageOrganization(user);
        boolean canManageWeekly = organizationService.canManageWeeklyReport(user);
        boolean hrOverview = hrService.canViewMemberHrOverview(user);
        boolean hrGlobal = hrService.canManageHr(user);
        boolean divisionLeader = hrService.isDivisionLeader(user);

        // [UX51] 큰 메뉴(그룹) 카드 — 카드 안에 접근 가능한 세부 메뉴 링크를 나열
        List<LaunchGroup> groups = new ArrayList<>();
        for (SystemSettingsService.ExtMenuGroup group : SystemSettingsService.EXTERNAL_MENU_TREE) {
            if ("dashboard".equals(group.key()) || hidden.contains(group.key())) continue;
            List<LaunchCard> items = new ArrayList<>();
            for (SystemSettingsService.ExtMenuItem item : group.children()) {
                if (hidden.contains(item.key()) || item.paths().stream().noneMatch(releaseFeatures::allowsPath)) continue;
                if (!allowed(item.key(), user, canManageOrg, canManageWeekly, hrOverview, hrGlobal,
                        divisionLeader)) continue;
                items.add(new LaunchCard(group.key(), group.label(), item.label(),
                        resolveUrl(item)));
            }
            if (!items.isEmpty()) {
                groups.add(new LaunchGroup(group.key(), group.label(), items));
            }
        }
        model.addAttribute("title", "메뉴");
        model.addAttribute("currentUser", user);
        model.addAttribute("launchGroups", groups);
        return "launcher";
    }

    /** 메뉴의 대표 경로를 사용한다. */
    private String resolveUrl(SystemSettingsService.ExtMenuItem item) {
        return item.paths().isEmpty() ? "/" : item.paths().getFirst();
    }

    /** 사이드바(fragments/app)와 동일한 노출 조건 — 메뉴 추가/권한 변경 시 함께 갱신. */
    private boolean allowed(String key, CustomUserDetails user,
                            boolean canManageOrg, boolean canManageWeekly,
                            boolean hrOverview, boolean hrGlobal,
                            boolean divisionLeader) {
        return switch (key) {
            case "project.manage", "report.my", "report.team", "report.search",
                 "hr.orgChart", "hr.attendance", "hr.schedules", "hr.loa",
                 "doc.approval", "doc.io", "hr.delegations" -> true;
            case "report.admin" -> canManageWeekly || divisionLeader;
            case "org.settings" -> user.hasRole("SYSTEM_ADMIN");
            case "org.org", "org.users", "org.approvalRoutes", "org.hrDiagnostics" -> canManageOrg;
            case "communication.notices" -> user.isSuperAdmin() || user.hasRole("SYSTEM_ADMIN") || user.hasRole("NOTICE_ADMIN");
            case "super.releases", "super.system" -> user.isSuperAdmin();
            case "members.overview", "members.matrix" -> hrOverview;
            case "members.leaveLedger", "members.attendancePolicy" -> hrGlobal;
            default -> false;
        };
    }
}
