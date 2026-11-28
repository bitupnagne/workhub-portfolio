package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.ApprovalRouteChainForm;
import com.portfolio.worksystem.dto.NoticeForm;
import com.portfolio.worksystem.dto.SystemThemeForm;
import com.portfolio.worksystem.dto.TeamForm;
import com.portfolio.worksystem.dto.UserForm;
import com.portfolio.worksystem.dto.UserUpdateForm;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.ApproverType;
import com.portfolio.worksystem.model.RequesterLevel;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.ApprovalService;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.CommunicationService;
import com.portfolio.worksystem.service.OrganizationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@RequiredArgsConstructor
@Controller
public class AdminController {
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;
    private final OrganizationService organizationService;
    private final ApprovalService approvalService;
    private final CommunicationService communicationService;
    private final com.portfolio.worksystem.service.AccountService accountService;
    private final com.portfolio.worksystem.service.SystemSettingsService systemSettingsService;
    private final com.portfolio.worksystem.service.EmpGradeService empGradeService;

    @GetMapping("/admin/settings")
    public String settings(@AuthenticationPrincipal CustomUserDetails user,
                           @RequestParam(required = false, name = "q") String query,
                           @RequestParam(required = false) Long teamId,
                           @RequestParam(defaultValue = "all") String role,
                           @RequestParam(defaultValue = "1") int page,
                           Model model) {
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        List<UserAccount> filtered = organizationService.adminManagedUsers().stream()
                .filter(u -> teamId == null || teamId.equals(u.getTeamId()))
                .filter(u -> "all".equals(role) || (u.getRoles() != null && u.getRoles().contains(role)))
                .filter(u -> keyword.isEmpty()
                        || containsIgnoreCase(u.getName(), keyword)
                        || containsIgnoreCase(u.getUsername(), keyword)
                        || containsIgnoreCase(u.getTeamName(), keyword)
                        || containsIgnoreCase(u.getJobTitle(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, filtered.size());
        addCommon(model, user, "관리자 지정", "settings");
        model.addAttribute("managedUsers", com.portfolio.worksystem.model.PageInfo.slice(filtered, pageInfo));
        model.addAttribute("manageableRoles", organizationService.manageableAdminRoles(user).stream()
                .filter(roleCode -> releaseFeatures.isAttendanceEnabled() || !"ERP_ADMIN".equals(roleCode))
                .filter(roleCode -> releaseFeatures.isReportsEnabled() || !"REPORT_ADMIN".equals(roleCode))
                .filter(roleCode -> releaseFeatures.isNoticesEnabled() || !"NOTICE_ADMIN".equals(roleCode)).toList());
        model.addAttribute("teams", organizationService.findMembershipTeams());
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/admin/settings?q="
                + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                + "&teamId=" + (teamId == null ? "" : teamId)
                + "&role=" + role + "&page=");
        model.addAttribute("query", keyword);
        model.addAttribute("teamFilter", teamId);
        model.addAttribute("roleFilter", role);
        return "admin/settings";
    }

    @PostMapping("/admin/settings/role")
    public String setAdminRole(@AuthenticationPrincipal CustomUserDetails user,
                               @RequestParam Long userId,
                               @RequestParam String roleCode,
                               @RequestParam boolean grant,
                               RedirectAttributes ra) {
        if ((!releaseFeatures.isAttendanceEnabled() && "ERP_ADMIN".equals(roleCode))
                || (!releaseFeatures.isReportsEnabled() && "REPORT_ADMIN".equals(roleCode))
                || (!releaseFeatures.isNoticesEnabled() && "NOTICE_ADMIN".equals(roleCode))) {
            throw new com.portfolio.worksystem.service.BusinessException("이 버전에서는 지정할 수 없는 권한입니다.");
        }
        runWithMessage(() -> organizationService.setUserAdminRole(userId, roleCode, grant, user), "관리자 권한을 변경했습니다.", ra);
        return "redirect:/admin/settings";
    }

    @GetMapping("/admin/system")
    public String systemManagement(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "시스템 관리", "system");
        model.addAttribute("theme", systemSettingsService.theme());
        model.addAttribute("themeMode", systemSettingsService.themeMode());
        model.addAttribute("themeProfiles", systemSettingsService.themeProfiles(user));
        model.addAttribute("activeThemeProfile", systemSettingsService.activeThemeProfile());
        model.addAttribute("branding", systemSettingsService.branding());
        model.addAttribute("logoStorageStatus", systemSettingsService.logoStorageStatus());
        model.addAttribute("deployIntegration", systemSettingsService.deployIntegration());
        model.addAttribute("otpRequireAdmin", systemSettingsService.otpRequireAdmin());
        model.addAttribute("otpRequireUser", systemSettingsService.otpRequireUser());
        model.addAttribute("otpRequireExternal", systemSettingsService.otpRequireExternal());
        model.addAttribute("allowedUploadExtensions", systemSettingsService.allowedUploadExtensionsCsv());
        return "admin/system";
    }

    /** OTP 정책 저장(관리자/사용자/외부망 OTP 필수 토글). 체크박스 미전송=false. */
    @PostMapping("/admin/system/otp-policy")
    public String saveOtpPolicy(@AuthenticationPrincipal CustomUserDetails user,
                                @RequestParam(value = "requireAdmin", required = false) boolean requireAdmin,
                                @RequestParam(value = "requireUser", required = false) boolean requireUser,
                                @RequestParam(value = "requireExternal", required = false) boolean requireExternal,
                                RedirectAttributes ra) {
        runWithMessage(() -> systemSettingsService.saveOtpPolicy(requireAdmin, requireUser, requireExternal, user),
                "OTP 정책을 저장했습니다.", ra);
        return "redirect:/admin/system";
    }

    /** [보안] 업로드 허용 확장자 화이트리스트 저장(채팅·자료전송 등에 적용). */
    @PostMapping("/admin/system/upload-extensions")
    public String saveUploadExtensions(@AuthenticationPrincipal CustomUserDetails user,
                                       @RequestParam(value = "extensions", required = false) String extensions,
                                       RedirectAttributes ra) {
        runWithMessage(() -> systemSettingsService.saveAllowedUploadExtensions(extensions, user),
                "허용 파일 확장자를 저장했습니다.", ra);
        return "redirect:/admin/system";
    }

    /** 외부망(DMZ) 메뉴 노출 관리 — SUPER_ADMIN. (/admin/system/** 매처로 보호됨) */
    @GetMapping("/admin/system/external-menu")
    public String externalMenu(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "내·외부망 메뉴 노출 관리", "externalMenu");
        model.addAttribute("menuTree", systemSettingsService.releaseMenuTree());
        model.addAttribute("shownMenus", systemSettingsService.externalShownMenus());
        // [UX50] 내부망 노출 토글 상태
        model.addAttribute("intShownMenus", systemSettingsService.internalShownMenus());
        return "admin/external-menu";
    }

    /** [UX50] 내/외부망 노출 메뉴 저장(스위치 ON=체크된 키만 노출). 그룹/하위 모두. */
    @PostMapping("/admin/system/external-menu")
    public String saveExternalMenu(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam(value = "extShown", required = false) java.util.List<String> extShown,
                                   @RequestParam(value = "intShown", required = false) java.util.List<String> intShown,
                                   RedirectAttributes ra) {
        runWithMessage(() -> systemSettingsService.saveMenuPolicy(extShown, intShown, user),
                "메뉴 노출 설정을 저장했습니다.", ra);
        return "redirect:/admin/system/external-menu";
    }

    @PostMapping("/admin/system/theme")
    public String saveSystemTheme(@AuthenticationPrincipal CustomUserDetails user,
                                  @ModelAttribute SystemThemeForm form,
                                  RedirectAttributes ra) {
        runWithMessage(() -> systemSettingsService.saveTheme(form, user), "시스템 색상을 저장했습니다.", ra);
        return "redirect:/admin/system";
    }

    @PostMapping("/admin/system/theme-mode")
    public String saveSystemThemeMode(@AuthenticationPrincipal CustomUserDetails user,
                                      @RequestParam String mode,
                                      RedirectAttributes ra) {
        runWithMessage(() -> systemSettingsService.saveThemeMode(mode, user), "웹 테마 모드를 저장했습니다.", ra);
        return "redirect:/admin/system";
    }

    @PostMapping("/admin/system/theme/profile/save")
    public String saveSystemThemeProfile(@AuthenticationPrincipal CustomUserDetails user,
                                         @RequestParam int profileNumber,
                                         RedirectAttributes ra) {
        runWithMessage(() -> systemSettingsService.saveCurrentThemeAsProfile(profileNumber, user), "색상 프로필을 저장했습니다.", ra);
        return "redirect:/admin/system";
    }

    @PostMapping("/admin/system/theme/profile/load")
    public String loadSystemThemeProfile(@AuthenticationPrincipal CustomUserDetails user,
                                         @RequestParam int profileNumber,
                                         RedirectAttributes ra) {
        runWithMessage(() -> systemSettingsService.loadThemeProfile(profileNumber, user), "색상 프로필을 불러왔습니다.", ra);
        return "redirect:/admin/system";
    }

    @PostMapping("/admin/system/logos")
    public String saveSystemLogos(@AuthenticationPrincipal CustomUserDetails user,
                                  @RequestParam(value = "webLogo", required = false) org.springframework.web.multipart.MultipartFile webLogo,
                                  @RequestParam(value = "desktopLogo", required = false) org.springframework.web.multipart.MultipartFile desktopLogo,
                                  RedirectAttributes ra) {
        runWithMessage(() -> systemSettingsService.saveLogos(webLogo, desktopLogo, user), "시스템 로고를 저장했습니다.", ra);
        return "redirect:/admin/system";
    }

    @PostMapping("/admin/system/deploy-integration")
    public String saveDeployIntegration(@AuthenticationPrincipal CustomUserDetails user,
                                        @RequestParam("deployBaseUrl") String deployBaseUrl,
                                        @RequestParam("sharedSecret") String sharedSecret,
                                        RedirectAttributes ra) {
        runWithMessage(
                () -> systemSettingsService.saveDeployIntegration(deployBaseUrl, sharedSecret, user),
                "배포시스템 연동 설정을 저장했습니다.",
                ra
        );
        return "redirect:/admin/system";
    }

    @GetMapping("/admin/approval-routes")
    public String approvalRoutes(@AuthenticationPrincipal CustomUserDetails user,
                                 @RequestParam(defaultValue = "LEAVE") ApprovalDocumentType type,
                                 Model model) {
        addCommon(model, user, type.getLabel() + " 결재 관리", "approvalRoutes");
        model.addAttribute("docType", type);
        model.addAttribute("documentTypes", ApprovalDocumentType.values());
        model.addAttribute("routes", approvalService.routeSummaries(type));
        return "admin/approval-routes";
    }

    @GetMapping("/admin/hr-diagnostics")
    public String hrDiagnostics(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "HR 운영 점검", "hrDiagnostics");
        model.addAttribute("organizationIssues", organizationService.organizationIssues(user));
        model.addAttribute("routeRuleIssues", approvalService.routeRuleIssues(user));
        model.addAttribute("routeReadinessIssues", approvalService.routeReadinessIssues(user));
        return "admin/hr-diagnostics";
    }

    @GetMapping("/admin/approval-routes/chain")
    public String approvalRouteChain(@AuthenticationPrincipal CustomUserDetails user,
                                     @RequestParam(defaultValue = "LEAVE") ApprovalDocumentType type,
                                     @RequestParam(required = false) RequesterLevel level,
                                     @RequestParam(required = false) Long scopeTeamId,
                                     Model model) {
        RequesterLevel target = level != null ? level : RequesterLevel.values()[0];
        addCommon(model, user, type.getLabel() + " 결재 경로 편집", "approvalRoutes");
        model.addAttribute("docType", type);
        if (!model.containsAttribute("chainForm")) {
            model.addAttribute("chainForm", approvalService.routeChain(type, target, scopeTeamId));
        }
        addRouteFormRefs(model, user);
        return "admin/approval-route-chain";
    }

    private void addRouteFormRefs(Model model, CustomUserDetails user) {
        model.addAttribute("documentTypes", ApprovalDocumentType.values());
        model.addAttribute("requesterLevels", RequesterLevel.values());
        model.addAttribute("approverTypes", ApproverType.values());
        model.addAttribute("users", organizationService.findUsers(user));
        model.addAttribute("receiverTeams", organizationService.findOrganizationNodes());
    }

    @PostMapping("/admin/approval-routes/chain")
    public String saveApprovalRouteChain(@AuthenticationPrincipal CustomUserDetails user,
                                         @Valid @ModelAttribute ApprovalRouteChainForm chainForm,
                                         BindingResult bindingResult,
                                         RedirectAttributes ra) {
        String type = chainForm.getDocumentType() == null ? "LEAVE" : chainForm.getDocumentType().name();
        String level = chainForm.getRequesterLevel() == null ? "" : chainForm.getRequesterLevel().name();
        String back = "redirect:/admin/approval-routes/chain?type=" + type
                + (level.isEmpty() ? "" : "&level=" + level)
                + (chainForm.getScopeTeamId() == null ? "" : "&scopeTeamId=" + chainForm.getScopeTeamId());
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", bindingResult.getAllErrors().get(0).getDefaultMessage());
            ra.addFlashAttribute("chainForm", chainForm);
            return back;
        }
        boolean ok = runWithMessage(() -> approvalService.saveRouteChain(chainForm, user),
                "결재 경로를 저장했습니다.", ra);
        if (ok) {
            return "redirect:/admin/approval-routes?type=" + type;
        }
        ra.addFlashAttribute("chainForm", chainForm);
        return back;
    }

    @PostMapping("/admin/approval-routes/chain/delete")
    public String deleteApprovalRouteChain(@AuthenticationPrincipal CustomUserDetails user,
                                           @RequestParam(defaultValue = "LEAVE") ApprovalDocumentType type,
                                           @RequestParam RequesterLevel level,
                                           @RequestParam(required = false) Long scopeTeamId,
                                           RedirectAttributes ra) {
        runWithMessage(() -> approvalService.deleteRouteChain(type, level, scopeTeamId, user),
                "결재 경로를 삭제했습니다.", ra);
        return "redirect:/admin/approval-routes?type=" + type.name();
    }

    @GetMapping("/admin/notices")
    public String notices(@AuthenticationPrincipal CustomUserDetails user,
                          @RequestParam(required = false, name = "q") String query,
                          @RequestParam(defaultValue = "all") String status,
                          @RequestParam(defaultValue = "1") int page,
                          Model model) {
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        String statusFilter = StringUtils.hasText(status) ? status.trim() : "all";
        List<com.portfolio.worksystem.model.AdminNotice> all = communicationService.adminNotices(user).stream()
                .filter(n -> "all".equals(statusFilter)
                        || ("active".equals(statusFilter) && Boolean.TRUE.equals(n.getActive()))
                        || ("inactive".equals(statusFilter) && !Boolean.TRUE.equals(n.getActive())))
                .filter(n -> keyword.isEmpty()
                        || containsIgnoreCase(n.getTitle(), keyword)
                        || containsIgnoreCase(n.getPlainBody(), keyword)
                        || containsIgnoreCase(n.getCreatedByName(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, all.size());
        addCommon(model, user, "관리자 공지", "notices");
        model.addAttribute("notices", com.portfolio.worksystem.model.PageInfo.slice(all, pageInfo));
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/admin/notices?q="
                + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                + "&status=" + statusFilter + "&page=");
        model.addAttribute("query", keyword);
        model.addAttribute("statusFilter", statusFilter);
        return "admin/notices";
    }

    @GetMapping("/admin/notices/new")
    public String newNotice(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "공지 작성", "notices");
        return "admin/notice-form";
    }

    @GetMapping("/admin/notices/{id}/edit")
    public String editNotice(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             Model model) {
        addCommon(model, user, "공지 수정", "notices");
        model.addAttribute("notice", communicationService.adminNotice(id, user));
        model.addAttribute("pollEdit", communicationService.pollForEdit(id, user));
        return "admin/notice-form";
    }

    @PostMapping("/admin/notices")
    public String createNotice(@AuthenticationPrincipal CustomUserDetails user,
                               @Valid @ModelAttribute NoticeForm form,
                               BindingResult bindingResult,
                               RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", bindingResult.getAllErrors().get(0).getDefaultMessage());
            ra.addFlashAttribute("noticeForm", form);
            return "redirect:/admin/notices/new";
        }
        boolean ok = runWithMessage(() -> communicationService.createAdminNotice(user, form), "공지사항을 등록했습니다.", ra);
        if (ok) {
            return "redirect:/admin/notices";
        }
        ra.addFlashAttribute("noticeForm", form);
        return "redirect:/admin/notices/new";
    }

    @PostMapping("/admin/notices/update")
    public String updateNotice(@AuthenticationPrincipal CustomUserDetails user,
                               @Valid @ModelAttribute NoticeForm form,
                               BindingResult bindingResult,
                               RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", bindingResult.getAllErrors().get(0).getDefaultMessage());
            ra.addFlashAttribute("noticeForm", form);
            return form.getId() == null ? "redirect:/admin/notices" : "redirect:/admin/notices/" + form.getId() + "/edit";
        }
        boolean ok = runWithMessage(() -> communicationService.updateAdminNotice(user, form), "공지사항을 저장했습니다.", ra);
        if (ok) {
            return "redirect:/admin/notices";
        }
        ra.addFlashAttribute("noticeForm", form);
        return form.getId() == null ? "redirect:/admin/notices" : "redirect:/admin/notices/" + form.getId() + "/edit";
    }

    @PostMapping("/admin/notices/{id}/delete")
    public String deleteNotice(@AuthenticationPrincipal CustomUserDetails user,
                               @PathVariable Long id,
                               RedirectAttributes ra) {
        runWithMessage(() -> communicationService.deleteAdminNotice(user, id), "공지사항을 삭제했습니다.", ra);
        return "redirect:/admin/notices";
    }

    @PostMapping("/admin/notices/image")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> uploadNoticeImage(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam("image") org.springframework.web.multipart.MultipartFile image) {
        try {
            String filename = accountService.storeNoticeImage(user, image);
            return org.springframework.http.ResponseEntity.ok(
                    java.util.Map.of("url", "/notice-images/" + filename));
        } catch (com.portfolio.worksystem.service.BusinessException e) {
            return org.springframework.http.ResponseEntity
                    .badRequest()
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/admin/org")
    public String organization(@AuthenticationPrincipal CustomUserDetails user,
                               @RequestParam(required = false, name = "q") String query,
                               @RequestParam(defaultValue = "all") String type,
                               @RequestParam(defaultValue = "1") int page,
                               Model model) {
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        String typeFilter = StringUtils.hasText(type) ? type.trim() : "all";
        List<com.portfolio.worksystem.model.Team> allNodes = organizationService.organizationNodesForManagement();
        List<com.portfolio.worksystem.model.Team> filteredNodes = allNodes.stream()
                .filter(t -> "all".equals(typeFilter) || typeFilter.equals(t.getNodeType()))
                .filter(t -> keyword.isEmpty()
                        || containsIgnoreCase(t.getName(), keyword)
                        || containsIgnoreCase(t.getCode(), keyword)
                        || containsIgnoreCase(t.getPath(), keyword)
                        || containsIgnoreCase(t.getLeaderName(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, filteredNodes.size());
        addCommon(model, user, "조직 관리", "org");
        model.addAttribute("organizationNodes", com.portfolio.worksystem.model.PageInfo.slice(filteredNodes, pageInfo));
        model.addAttribute("organizationTotalCount", allNodes.size());
        model.addAttribute("organizationTree", organizationService.findOrganizationTree());
        model.addAttribute("users", organizationService.findUsers(user));
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/admin/org?q="
                + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                + "&type=" + typeFilter + "&page=");
        model.addAttribute("query", keyword);
        model.addAttribute("typeFilter", typeFilter);
        return "admin/org";
    }

    @GetMapping("/admin/org/new")
    public String newTeam(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "조직 추가", "org");
        model.addAttribute("organizationNodes", organizationService.findOrganizationNodes());
        return "admin/org-new";
    }

    @GetMapping("/admin/org/{id}/edit")
    public String editTeam(@AuthenticationPrincipal CustomUserDetails user,
                           @PathVariable Long id,
                           Model model) {
        com.portfolio.worksystem.model.Team node = organizationService.organizationNodesForManagement().stream()
                .filter(t -> t.getId().equals(id)).findFirst().orElse(null);
        if (node == null) {
            return "redirect:/admin/org";
        }
        addCommon(model, user, node.getName() + " 조직 수정", "org");
        model.addAttribute("node", node);
        model.addAttribute("users", organizationService.findUsers(user));
        model.addAttribute("orderMembers", organizationService.orderableMembers(id, user));
        return "admin/org-edit";
    }

    @PostMapping("/admin/org/{id}/member-order")
    public String saveMemberOrder(@AuthenticationPrincipal CustomUserDetails user,
                                  @PathVariable Long id,
                                  @RequestParam(name = "order", required = false) List<Long> order,
                                  RedirectAttributes ra) {
        runWithMessage(() -> organizationService.saveMemberOrder(id, order, user),
                "직원 순서를 저장했습니다.", ra);
        return "redirect:/admin/org/" + id + "/edit";
    }

    @PostMapping("/admin/org/{id}/members/{userId}/remove")
    public String removeMemberFromOrganization(@AuthenticationPrincipal CustomUserDetails user,
                                               @PathVariable Long id,
                                               @PathVariable Long userId,
                                               RedirectAttributes ra) {
        runWithMessage(() -> organizationService.removeMemberFromOrganization(id, userId, user),
                "직원을 현재 조직에서 해제했습니다.", ra);
        return "redirect:/admin/org/" + id + "/edit";
    }

    @PostMapping("/admin/org/team")
    public String createTeam(@AuthenticationPrincipal CustomUserDetails user,
                             @Valid @ModelAttribute TeamForm form,
                             BindingResult bindingResult,
                             RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", bindingResult.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/admin/org/new";
        }
        runWithMessage(() -> organizationService.createTeam(form, user), "조직을 추가했습니다.", ra);
        return "redirect:/admin/org";
    }

    @PostMapping("/admin/org/leader")
    public String assignLeader(@AuthenticationPrincipal CustomUserDetails user,
                               @RequestParam Long teamId,
                               @RequestParam(required = false) Long leaderUserId,
                               RedirectAttributes ra) {
        runWithMessage(() -> organizationService.assignOrganizationLeader(teamId, leaderUserId, user), "조직장을 저장했습니다.", ra);
        return "redirect:/admin/org/" + teamId + "/edit";
    }

    @PostMapping("/admin/org/rename")
    public String renameTeam(@AuthenticationPrincipal CustomUserDetails user,
                             @RequestParam Long teamId,
                             @RequestParam String name,
                             RedirectAttributes ra) {
        runWithMessage(() -> organizationService.renameTeam(teamId, name, user), "조직 이름을 수정했습니다.", ra);
        return "redirect:/admin/org/" + teamId + "/edit";
    }

    @PostMapping("/admin/org/weekly-target")
    public String setWeeklyReportTarget(@AuthenticationPrincipal CustomUserDetails user,
                                        @RequestParam Long teamId,
                                        @RequestParam boolean target,
                                        RedirectAttributes ra) {
        runWithMessage(() -> organizationService.setWeeklyReportTarget(teamId, target, user),
                "주간보고 작성 대상 설정을 변경했습니다.", ra);
        return "redirect:/admin/org/" + teamId + "/edit";
    }


    @PostMapping("/admin/org/{id}/delete")
    public String deleteTeam(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             RedirectAttributes ra) {
        runWithMessage(() -> organizationService.deleteTeam(id, user), "조직이 삭제되었습니다.", ra);
        return "redirect:/admin/org";
    }

    @GetMapping("/admin/users")
    public String users(@AuthenticationPrincipal CustomUserDetails user,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) Long teamId,
                        @RequestParam(defaultValue = "all") String status,
                        @RequestParam(defaultValue = "all") String contractor,
                        @RequestParam(defaultValue = "1") int page,
                        Model model) {
        addCommon(model, user, "직원 관리", "users");
        List<UserAccount> allUsers = organizationService.findAllUsersForManagement(user);
        List<UserAccount> users = organizationService.findUsersForManagement(user, q, teamId, status, contractor);
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, users.size());
        String pageBaseUrl = "/admin/users?q=" + java.net.URLEncoder.encode(q == null ? "" : q, java.nio.charset.StandardCharsets.UTF_8)
                + "&teamId=" + (teamId == null ? "" : teamId) + "&status=" + status + "&contractor=" + contractor + "&page=";
        model.addAttribute("teams", organizationService.findMembershipTeams());
        model.addAttribute("users", com.portfolio.worksystem.model.PageInfo.slice(users, pageInfo));
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", pageBaseUrl);
        model.addAttribute("totalUserCount", allUsers.size());
        model.addAttribute("activeUserCount", allUsers.stream().filter(u -> Boolean.TRUE.equals(u.getActive())).count());
        model.addAttribute("inactiveUserCount", allUsers.stream().filter(u -> !Boolean.TRUE.equals(u.getActive())).count());
        model.addAttribute("query", q == null ? "" : q);
        model.addAttribute("teamFilter", teamId);
        model.addAttribute("statusFilter", status);
        model.addAttribute("contractorFilter", contractor);
        return "admin/users";
    }

    @GetMapping("/admin/users/new")
    public String newUser(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "직원 등록", "users");
        model.addAttribute("teams", organizationService.findMembershipTeams());
        model.addAttribute("grades", empGradeService.activeGrades());
        return "admin/user-new";
    }

    /** [UX12] 직원 수정은 인사카드의 ⚙(정보 수정) 모달로 통합 — 구 URL은 리다이렉트.
     *  [UX29] 진입 시 모달을 바로 띄우지 않는다(카드 확인 후 ⚙로 수정). */
    @GetMapping("/admin/users/{id}/edit")
    public String editUser(@PathVariable Long id) {
        return personnelRedirect(id);
    }

    /** [UX35] 비밀번호 초기화 — 인사카드 정보 수정 모달에서 사용. */
    @PostMapping("/admin/users/{id}/password-reset")
    public String resetPassword(@AuthenticationPrincipal CustomUserDetails user,
                                @PathVariable Long id,
                                RedirectAttributes ra) {
        runWithMessage(() -> organizationService.resetPassword(id, user),
                "비밀번호를 초기화했습니다. 관리자가 별도로 전달한 초기 비밀번호를 사용하고 다음 로그인 시 변경해 주세요.", ra);
        return personnelRedirect(id);
    }

    /** [보안] OTP 강제 초기화 — 핸드폰 분실 등으로 재등록이 필요할 때. 대상자는 다음 로그인 시 재등록. */
    @PostMapping("/admin/users/{id}/otp-reset")
    public String resetOtp(@AuthenticationPrincipal CustomUserDetails user,
                           @PathVariable Long id,
                           RedirectAttributes ra) {
        runWithMessage(() -> organizationService.resetOtp(id, user),
                "OTP를 초기화했습니다. 대상자는 다음 로그인 시 2차 인증을 다시 등록합니다.", ra);
        return personnelRedirect(id);
    }

    @PostMapping("/admin/users/{id}/signature")
    public String adminUploadSignature(@AuthenticationPrincipal CustomUserDetails user,
                                       @PathVariable Long id,
                                       @RequestParam("signature") org.springframework.web.multipart.MultipartFile signature,
                                       RedirectAttributes ra) {
        runWithMessage(() -> accountService.updateSignatureImageForUser(user, id, signature), "직원 서명을 저장했습니다.", ra);
        return "redirect:/hr/members/" + id + "?edit=1";
    }

    @PostMapping("/admin/users/{id}/signature/delete")
    public String adminDeleteSignature(@AuthenticationPrincipal CustomUserDetails user,
                                       @PathVariable Long id,
                                       RedirectAttributes ra) {
        runWithMessage(() -> accountService.deleteSignatureForUser(user, id), "직원 서명을 삭제했습니다.", ra);
        return "redirect:/hr/members/" + id + "?edit=1";
    }

    @PostMapping("/admin/users")
    public String createUser(@AuthenticationPrincipal CustomUserDetails user,
                             @Valid @ModelAttribute UserForm form,
                             BindingResult bindingResult,
                             RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", bindingResult.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/admin/users/new";
        }
        runWithMessage(() -> organizationService.createUser(form, user),
                "직원을 등록했습니다. 초기 비밀번호는 관리자가 안전한 경로로 별도 전달합니다.", ra);
        return "redirect:/people?view=list";
    }

    @PostMapping("/admin/users/update")
    public String updateUser(@AuthenticationPrincipal CustomUserDetails user,
                             @Valid @ModelAttribute UserUpdateForm form,
                             BindingResult bindingResult,
                             @RequestParam(defaultValue = "directory") String returnTo,
                             RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", bindingResult.getAllErrors().get(0).getDefaultMessage());
            Long id = form.id();
            // 검증 오류는 수정 모달을 다시 열어 바로 고치게 한다(edit=1)
            return id == null ? "redirect:/admin/users" : ("hr-card".equals(returnTo)
                    ? "redirect:/hr/members/" + id + "?edit=1" : "redirect:/people/" + id + "?edit=1");
        }
        runWithMessage(() -> organizationService.updateUser(form, user), "직원 정보를 저장했습니다.", ra);
        if ("hr-card".equals(returnTo)) return "redirect:/hr/members/" + form.id();
        return "redirect:/people?view=list";
    }

    @PostMapping("/admin/users/{id}/delete")
    public String deleteUser(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             RedirectAttributes ra) {
        runWithMessage(() -> organizationService.deleteUser(id, user), "직원이 삭제되었습니다.", ra);
        return "redirect:/people?view=list";
    }

    private String personnelRedirect(Long id) {
        return "redirect:" + (releaseFeatures.isAttendanceEnabled() ? "/hr/members/" : "/people/") + id;
    }

    private void addCommon(Model model, CustomUserDetails user, String title, String active) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", title);
        model.addAttribute("active", active);
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
    }

    private boolean runWithMessage(Runnable action, String success, RedirectAttributes ra) {
        try {
            action.run();
            ra.addFlashAttribute("message", success);
            return true;
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return false;
        }
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return StringUtils.hasText(value)
                && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }
}
