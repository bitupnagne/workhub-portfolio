package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.model.AdminMenu;
import com.portfolio.worksystem.model.AdminMenuGrant;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.AdminPermissionService;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.OrganizationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * [PERM] 관리자 권한 관리 — 관리자 메뉴 한 줄에 하나씩, 펼치면 지금 권한을 가진 사람·조직장 자리가 보이고
 * '권한 주기'로 사람이나 조직장 자리에 준다. 주고 빼기는 화면을 다시 그리지 않도록 JSON 으로 주고받는다.
 */
@Controller
@RequiredArgsConstructor
public class AdminPermissionController {
    private final AdminPermissionService permissionService;
    private final OrganizationService organizationService;

    /** 권한을 줄 수 있는 직원 한 명. */
    public record Candidate(Long id, String name, String detail) {
    }

    /** 화면에 그릴 메뉴 한 줄. */
    public record MenuRow(AdminMenu menu, List<AdminMenuGrant> grants, boolean grantable, String lockReason) {
    }

    @GetMapping("/admin/permissions")
    public String permissions(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", "관리자 권한 관리");
        model.addAttribute("active", "permissions");
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));

        Map<String, List<MenuRow>> groups = new LinkedHashMap<>();
        permissionService.grantsByMenu().forEach((menu, grants) -> {
            boolean grantable = permissionService.canGrant(user, menu);
            String lock = null;
            if (!menu.isGrantable()) {
                lock = "슈퍼관리자 전용 메뉴입니다";
            } else if (!grantable && menu.isSuperOnlyGrant()) {
                lock = "슈퍼관리자만 줄 수 있습니다";
            }
            groups.computeIfAbsent(menu.getGroup(), key -> new ArrayList<>())
                    .add(new MenuRow(menu, grants, grantable, lock));
        });
        model.addAttribute("menuGroups", groups);
        model.addAttribute("leaderSeats", permissionService.leaderSeats());
        model.addAttribute("candidates", permissionService.candidates().stream()
                .map(u -> new Candidate(u.getId(), u.getName(), userDetail(u))).toList());
        return "admin/permissions";
    }

    @PostMapping("/admin/permissions/grant")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> grant(@AuthenticationPrincipal CustomUserDetails user,
                                                     @RequestParam String menuKey,
                                                     @RequestParam String granteeType,
                                                     @RequestParam Long targetId) {
        try {
            AdminMenuGrant grant = permissionService.grant(user, menuKey, granteeType, targetId);
            return ResponseEntity.ok(Map.of("ok", true, "grant", view(grant)));
        } catch (BusinessException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/admin/permissions/revoke")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> revoke(@AuthenticationPrincipal CustomUserDetails user,
                                                      @RequestParam Long grantId) {
        try {
            AdminMenuGrant grant = permissionService.revoke(user, grantId);
            return ResponseEntity.ok(Map.of("ok", true, "grantId", grant.getId(), "menuKey", grant.getMenuKey()));
        } catch (BusinessException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    private static Map<String, Object> view(AdminMenuGrant grant) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", grant.getId());
        view.put("menuKey", grant.getMenuKey());
        view.put("type", grant.getGranteeType());
        view.put("targetId", grant.isLeaderSeat() ? grant.getTeamId() : grant.getUserId());
        view.put("label", grant.getLabel());
        view.put("detail", grant.getDetail());
        return view;
    }

    /** 템플릿 보조 — 조직장 자리 보조 문구. */
    public static String seatDetail(Team team) {
        return team.getLeaderName() == null ? "공석" : "현재 " + team.getLeaderName();
    }

    /** 템플릿 보조 — 사람 보조 문구. */
    public static String userDetail(UserAccount user) {
        String team = user.getTeamName() == null ? "" : user.getTeamName();
        String title = user.getJobTitle() == null ? "" : user.getJobTitle();
        return (team + (team.isEmpty() || title.isEmpty() ? "" : " · ") + title).trim();
    }
}
