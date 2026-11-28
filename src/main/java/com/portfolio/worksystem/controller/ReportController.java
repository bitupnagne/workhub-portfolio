package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.ReportDetailForm;
import com.portfolio.worksystem.dto.ReportItemBundleForm;
import com.portfolio.worksystem.dto.ReportItemForm;
import com.portfolio.worksystem.model.ItemStatus;
import com.portfolio.worksystem.model.MemberStatus;
import com.portfolio.worksystem.model.ReportItem;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.TeamWeeklyStatus;
import com.portfolio.worksystem.model.WeeklyReport;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.ReportService;
import com.portfolio.worksystem.service.WeekUtils;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@RequiredArgsConstructor
@Controller
public class ReportController {
    private final ReportService reportService;
    private final OrganizationService organizationService;
    private final com.portfolio.worksystem.service.HrService hrService;

    @GetMapping("/report/my")
    public String myReport(@AuthenticationPrincipal CustomUserDetails user,
                           @RequestParam(required = false) String week,
                           Model model) {
        String weekKey = resolveWeek(week);
        reportService.syncProjectActivities(user, weekKey);
        WeeklyReport report = reportService.getOrCreateReport(user.getUserId(), weekKey);
        Map<ItemStatus, List<ReportItem>> items = reportService.myItems(user.getUserId(), weekKey);
        TeamWeeklyStatus teamStatus = reportService.teamStatus(user.getTeamId(), weekKey);
        boolean teamSubmitted = teamStatus != null && Boolean.TRUE.equals(teamStatus.getSubmitted());
        boolean confirmed = reportService.isWeekConfirmed(weekKey);
        boolean canEditReport = !confirmed && !teamSubmitted && !Boolean.TRUE.equals(report.getSubmitted());

        addCommon(model, user, weekKey, "내 주간보고", "my");
        addItemStats(model, items);
        model.addAttribute("report", report);
        model.addAttribute("items", items);
        model.addAttribute("statuses", ItemStatus.values());
        model.addAttribute("teamSubmitted", teamSubmitted);
        model.addAttribute("confirmed", confirmed);
        model.addAttribute("canEditReport", canEditReport);
        model.addAttribute("importableWeeks", reportService.weeksWithData().stream()
                .filter(dataWeek -> !weekKey.equals(dataWeek))
                .toList());
        return "report/my-report";
    }

    @PostMapping("/report/project-note")
    public String saveProjectNote(@AuthenticationPrincipal CustomUserDetails user,
                                  @RequestParam Long itemId, @RequestParam String weekKey,
                                  @RequestParam(required = false) String note, RedirectAttributes ra) {
        runWithMessage(() -> reportService.saveProjectNote(user, itemId, note), "보충 설명이 저장되었습니다.", ra);
        return redirectMy(resolveWeek(weekKey));
    }

    @PostMapping("/report/item")
    public String saveItem(@AuthenticationPrincipal CustomUserDetails user,
                           @Valid @ModelAttribute ReportItemForm form,
                           RedirectAttributes ra) {
        runWithMessage(() -> reportService.saveItem(form, user), "항목이 저장되었습니다.", ra);
        return redirectMy(form.weekKey());
    }

    @PostMapping("/report/item/bundle")
    public String saveItemBundle(@AuthenticationPrincipal CustomUserDetails user,
                                 @ModelAttribute ReportItemBundleForm form,
                                 RedirectAttributes ra) {
        runWithMessage(() -> reportService.saveItemBundle(form, user), "항목이 저장되었습니다.", ra);
        return redirectMy(form.getWeekKey());
    }

    @PostMapping("/report/item/delete")
    public String deleteItem(@AuthenticationPrincipal CustomUserDetails user,
                             @RequestParam Long itemId,
                             @RequestParam String weekKey,
                             RedirectAttributes ra) {
        runWithMessage(() -> reportService.deleteItem(itemId, user), "항목을 삭제했습니다.", ra);
        return redirectMy(weekKey);
    }

    @PostMapping("/report/item/move")
    public String moveItem(@AuthenticationPrincipal CustomUserDetails user,
                           @RequestParam Long itemId,
                           @RequestParam int direction,
                           @RequestParam String weekKey,
                           RedirectAttributes ra) {
        runWithMessage(() -> reportService.moveMyItem(user, itemId, direction), "항목 순서를 변경했습니다.", ra);
        return redirectMy(weekKey);
    }

    @PostMapping("/report/item/status")
    public String changeItemStatus(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam Long itemId,
                                   @RequestParam ItemStatus status,
                                   @RequestParam String weekKey,
                                   RedirectAttributes ra) {
        runWithMessage(() -> reportService.changeMyItemStatus(user, itemId, status), "항목 섹션을 변경했습니다.", ra);
        return redirectMy(weekKey);
    }

    @PostMapping("/report/import")
    public String importReport(@AuthenticationPrincipal CustomUserDetails user,
                               @RequestParam String sourceWeekKey,
                               @RequestParam String weekKey,
                               RedirectAttributes ra) {
        runWithMessage(() -> reportService.importFromWeek(user, sourceWeekKey, weekKey), "선택한 주차의 항목을 가져왔습니다.", ra);
        return redirectMy(weekKey);
    }

    @PostMapping("/report/detail")
    public String saveDetail(@AuthenticationPrincipal CustomUserDetails user,
                             @Valid @ModelAttribute ReportDetailForm form,
                             RedirectAttributes ra) {
        runWithMessage(() -> reportService.saveDetail(form, user), "세부 항목이 저장되었습니다.", ra);
        return redirectMy(form.weekKey());
    }

    @PostMapping("/report/detail/delete")
    public String deleteDetail(@AuthenticationPrincipal CustomUserDetails user,
                               @RequestParam Long detailId,
                               @RequestParam String weekKey,
                               RedirectAttributes ra) {
        runWithMessage(() -> reportService.deleteDetail(detailId, user), "세부 항목을 삭제했습니다.", ra);
        return redirectMy(weekKey);
    }

    @PostMapping("/report/submit")
    public String submit(@AuthenticationPrincipal CustomUserDetails user,
                         @RequestParam String weekKey,
                         RedirectAttributes ra) {
        runWithMessage(() -> reportService.submitMyReport(user, weekKey), "보고가 제출되었습니다.", ra);
        return redirectMy(weekKey);
    }

    @PostMapping("/report/recall")
    public String recall(@AuthenticationPrincipal CustomUserDetails user,
                         @RequestParam String weekKey,
                         RedirectAttributes ra) {
        runWithMessage(() -> reportService.recallMyReport(user, weekKey), "제출을 회수했습니다.", ra);
        return redirectMy(weekKey);
    }

    @GetMapping("/report/team")
    public String teamReport(@AuthenticationPrincipal CustomUserDetails user,
                             @RequestParam(required = false) String week,
                             Model model) {
        String weekKey = resolveWeek(week);
        Map<ItemStatus, List<ReportItem>> items = reportService.teamItems(user.getTeamId(), weekKey);
        List<MemberStatus> members = reportService.memberStatuses(user.getTeamId(), weekKey);
        TeamWeeklyStatus teamStatus = reportService.teamStatus(user.getTeamId(), weekKey);
        boolean teamSubmitted = teamStatus != null && Boolean.TRUE.equals(teamStatus.getSubmitted());
        boolean confirmed = reportService.isWeekConfirmed(weekKey);

        addCommon(model, user, weekKey, "팀원 현황 · 취합", "team");
        addItemStats(model, items);
        model.addAttribute("members", members);
        model.addAttribute("memberCount", members.size());
        model.addAttribute("submittedMemberCount", members.stream().filter(MemberStatus::getSubmitted).count());
        model.addAttribute("items", items);
        model.addAttribute("statuses", ItemStatus.values());
        model.addAttribute("teamSubmitted", teamSubmitted);
        model.addAttribute("confirmed", confirmed);
        model.addAttribute("canEditTeamReport", user.isTeamLeader() && !confirmed && !teamSubmitted);
        return "report/team-report";
    }

    @PostMapping("/report/team/item/move")
    public String moveTeamItem(@AuthenticationPrincipal CustomUserDetails user,
                               @RequestParam Long itemId,
                               @RequestParam int direction,
                               @RequestParam String weekKey,
                               RedirectAttributes ra) {
        runWithMessage(() -> reportService.moveTeamItem(user, itemId, direction), "취합 항목 순서를 변경했습니다.", ra);
        return "redirect:/report/team?week=" + weekKey;
    }

    @PostMapping("/report/team/item/status")
    public String changeTeamItemStatus(@AuthenticationPrincipal CustomUserDetails user,
                                       @RequestParam Long itemId,
                                       @RequestParam ItemStatus status,
                                       @RequestParam String weekKey,
                                       RedirectAttributes ra) {
        runWithMessage(() -> reportService.changeTeamItemStatus(user, itemId, status), "취합 항목 섹션을 변경했습니다.", ra);
        return "redirect:/report/team?week=" + weekKey;
    }

    @PostMapping("/report/team-submit")
    public String teamSubmit(@AuthenticationPrincipal CustomUserDetails user,
                             @RequestParam String weekKey,
                             RedirectAttributes ra) {
        runWithMessage(() -> reportService.teamSubmit(user, weekKey), "팀 보고가 최종 제출되었습니다.", ra);
        return "redirect:/report/team?week=" + weekKey;
    }

    @PostMapping("/report/team-recall")
    public String teamRecall(@AuthenticationPrincipal CustomUserDetails user,
                             @RequestParam String weekKey,
                             RedirectAttributes ra) {
        runWithMessage(() -> reportService.teamRecall(user, weekKey), "최종 제출을 회수했습니다.", ra);
        return "redirect:/report/team?week=" + weekKey;
    }

    @GetMapping("/report/admin")
    public String adminReport(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam(required = false) String week,
                              Model model) {
        boolean canManageWeeklyReport = organizationService.canManageWeeklyReport(user);
        boolean isDivisionLeader = hrService.isDivisionLeader(user);
        if (!canManageWeeklyReport && !isDivisionLeader) {
            throw new AccessDeniedException("주간보고 관리자 또는 본부장 권한이 필요합니다.");
        }
        String weekKey = resolveWeek(week);
        Map<Team, Map<ItemStatus, List<ReportItem>>> submittedItems = reportService.adminItems(weekKey);
        // [Q-3 fix] Team은 @Data라 equals가 전체 필드 비교 → orgTree(본부장)·findAll(취합맵) 출신 Team이 불일치.
        //           팀 객체가 아니라 team.id 로 조회해야 취합 항목이 정상 매칭됨.
        Map<Long, Map<ItemStatus, List<ReportItem>>> itemsByTeamId = indexByTeamId(submittedItems);
        // [Q-3] 본부장은 산하 팀만, 관리자는 전체
        List<Team> teams = canManageWeeklyReport
                ? organizationService.weeklyReportTeams()
                : hrService.reportVisibleTeams(user).stream()
                    .filter(t -> !Boolean.FALSE.equals(t.getWeeklyReportTarget()))
                    .toList();
        Map<Long, Boolean> teamSubmittedById = new HashMap<>();
        Map<Long, Integer> teamMemberCountById = new HashMap<>();
        Map<Long, Long> teamSubmittedMemberCountById = new HashMap<>();
        Map<Long, Integer> teamItemCountById = new HashMap<>();
        Map<Team, Map<ItemStatus, List<ReportItem>>> adminItems = new LinkedHashMap<>();

        for (Team team : teams) {
            TeamWeeklyStatus status = reportService.teamStatus(team.getId(), weekKey);
            boolean submitted = status != null && Boolean.TRUE.equals(status.getSubmitted());
            List<MemberStatus> memberStatuses = reportService.memberStatuses(team.getId(), weekKey);
            Map<ItemStatus, List<ReportItem>> grouped = itemsByTeamId.getOrDefault(team.getId(), emptyItems());
            int itemCount = grouped.values().stream().mapToInt(List::size).sum();
            teamSubmittedById.put(team.getId(), submitted);
            teamMemberCountById.put(team.getId(), memberStatuses.size());
            teamSubmittedMemberCountById.put(team.getId(), memberStatuses.stream().filter(MemberStatus::getSubmitted).count());
            teamItemCountById.put(team.getId(), itemCount);
            if (submitted) {
                adminItems.put(team, grouped);
            }
        }

        addCommon(model, user, weekKey, "전체 현황 · 취합", "admin");
        model.addAttribute("adminItems", adminItems);
        model.addAttribute("teams", teams);
        model.addAttribute("teamCount", teams.size());
        model.addAttribute("submittedTeamCount", teamSubmittedById.values().stream().filter(Boolean::booleanValue).count());
        model.addAttribute("unsubmittedTeamCount", teamSubmittedById.values().stream().filter(submitted -> !submitted).count());
        model.addAttribute("adminItemCount", adminItems.values().stream()
                .flatMap(group -> group.values().stream())
                .mapToInt(List::size)
                .sum());
        model.addAttribute("teamSubmittedById", teamSubmittedById);
        model.addAttribute("teamMemberCountById", teamMemberCountById);
        model.addAttribute("teamSubmittedMemberCountById", teamSubmittedMemberCountById);
        model.addAttribute("teamItemCountById", teamItemCountById);
        model.addAttribute("confirmed", reportService.isWeekConfirmed(weekKey));
        // [Q-3] 본부장은 view만 — 편집 컨트롤 분기에 사용
        model.addAttribute("canEditAdminReport", canManageWeeklyReport);
        return "report/admin-report";
    }

    @PostMapping("/report/admin/team-order")
    public String moveAdminTeam(@AuthenticationPrincipal CustomUserDetails user,
                                @RequestParam Long teamId,
                                @RequestParam int direction,
                                @RequestParam String weekKey,
                                RedirectAttributes ra) {
        runWithMessage(() -> reportService.moveAdminTeam(user, weekKey, teamId, direction), "팀 표시 순서를 변경했습니다.", ra);
        return "redirect:/report/admin?week=" + weekKey;
    }

    @PostMapping("/report/admin-confirm")
    public String adminConfirm(@AuthenticationPrincipal CustomUserDetails user,
                               @RequestParam String weekKey,
                               RedirectAttributes ra) {
        runWithMessage(() -> reportService.confirmWeek(user, weekKey), "주간보고가 확정되었습니다.", ra);
        return "redirect:/report/admin?week=" + weekKey;
    }

    @PostMapping("/report/admin-cancel")
    public String adminCancel(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam String weekKey,
                              RedirectAttributes ra) {
        runWithMessage(() -> reportService.cancelWeek(user, weekKey), "확정을 취소했습니다.", ra);
        return "redirect:/report/admin?week=" + weekKey;
    }

    @GetMapping("/report/print")
    public String printReport(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam(defaultValue = "my") String scope,
                              @RequestParam(required = false) String week,
                              Model model) {
        String weekKey = resolveWeek(week);
        addCommon(model, user, weekKey, "주간보고 출력", scope, false);
        model.addAttribute("statuses", ItemStatus.values());
        model.addAttribute("printScope", scope);
        model.addAttribute("printTitle", printTitle(scope, user));
        model.addAttribute("printDate", LocalDate.now());
        model.addAttribute("confirmed", reportService.isWeekConfirmed(weekKey));

        switch (scope) {
            case "team" -> {
                model.addAttribute("items", completeItems(reportService.teamItems(user.getTeamId(), weekKey)));
                model.addAttribute("printOwner", user.getTeamName());
            }
            case "admin" -> {
                // [Q-3] 관리자는 전체, 본부장은 산하팀만 — 화면(/report/admin)과 동일한 스코프로 출력
                if (organizationService.canManageWeeklyReport(user)) {
                    model.addAttribute("adminItems", visibleAdminItems(weekKey, organizationService.weeklyReportTeams()));
                    model.addAttribute("printOwner", organizationService.getReportAdminTeam().getName());
                } else if (hrService.isDivisionLeader(user)) {
                    List<Team> visible = hrService.reportVisibleTeams(user);
                    List<Team> printTeams = visible.stream()
                            .filter(t -> !Boolean.FALSE.equals(t.getWeeklyReportTarget()))
                            .toList();
                    model.addAttribute("adminItems", visibleAdminItems(weekKey, printTeams));
                    model.addAttribute("printTitle", "본부 주간보고");
                    model.addAttribute("printOwner", visible.stream()
                            .filter(t -> "DIVISION".equals(t.getNodeType()))
                            .map(Team::getName)
                            .findFirst()
                            .orElse("본부"));
                } else {
                    throw new AccessDeniedException("주간보고 관리자 또는 본부장 권한이 필요합니다.");
                }
            }
            default -> {
                reportService.syncProjectActivities(user, weekKey);
                model.addAttribute("items", completeItems(reportService.myItems(user.getUserId(), weekKey)));
                model.addAttribute("printOwner", user.getName());
            }
        }
        reportService.logPrint(user, weekKey, scope);
        return "report/print";
    }

    @GetMapping("/report/search")
    public String search(@AuthenticationPrincipal CustomUserDetails user,
                         @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "1") int page,
                         Model model) {
        String query = StringUtils.hasText(q) ? q.trim() : "";
        boolean canManageWeeklyReport = organizationService.canManageWeeklyReport(user);
        boolean isDivisionLeader = hrService.isDivisionLeader(user);
        java.util.List<com.portfolio.worksystem.model.SearchResult> all = reportService.search(query, null, user);
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, all.size());

        addCommon(model, user, reportService.defaultWeekKey(), "주간보고 검색", "search", false);
        model.addAttribute("query", query);
        model.addAttribute("searchScopeLabel", "전체 주차");
        // [#6] 본부장(divisionLeader)도 산하 직원 검색 결과를 보므로 담당자 컬럼 노출 + 전체현황으로 바로가기
        model.addAttribute("showOwnerColumns", canManageWeeklyReport || isDivisionLeader || user.isTeamLeader());
        model.addAttribute("searchGoPath", (canManageWeeklyReport || isDivisionLeader) ? "/report/admin" : user.isTeamLeader() ? "/report/team" : "/report/my");
        model.addAttribute("results", com.portfolio.worksystem.model.PageInfo.slice(all, pageInfo));
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/report/search?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) + "&page=");
        return "report/search";
    }

    private void addCommon(Model model, CustomUserDetails user, String weekKey, String title, String active) {
        addCommon(model, user, weekKey, title, active, true);
    }

    private void addCommon(Model model, CustomUserDetails user, String weekKey, String title, String active, boolean showWeekControl) {
        model.addAttribute("currentUser", user);
        model.addAttribute("weekKey", weekKey);
        model.addAttribute("weekLabel", WeekUtils.getWeekLabel(weekKey));
        model.addAttribute("weekPeriod", WeekUtils.getWeekPeriod(weekKey));
        var projectPeriodFormat = java.time.format.DateTimeFormatter.ofPattern("MM/dd");
        var projectWeekStart = WeekUtils.getWeekStart(weekKey);
        model.addAttribute("projectWeekPeriod", projectPeriodFormat.format(projectWeekStart) + " ~ "
                + projectPeriodFormat.format(projectWeekStart.plusDays(6)));
        model.addAttribute("prevWeekKey", WeekUtils.shiftWeek(weekKey, -1));
        model.addAttribute("nextWeekKey", WeekUtils.shiftWeek(weekKey, 1));
        model.addAttribute("currentWeekKey", WeekUtils.getCurrentWeekKey());
        model.addAttribute("currentPath", currentPath(active));
        model.addAttribute("title", title);
        model.addAttribute("active", active);
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
        model.addAttribute("weeksWithData", reportService.weeksWithData());
        model.addAttribute("showWeekControl", showWeekControl);
    }

    private void addItemStats(Model model, Map<ItemStatus, List<ReportItem>> items) {
        int newCount = items.getOrDefault(ItemStatus.NEW, List.of()).size();
        int continueCount = items.getOrDefault(ItemStatus.CONTINUE, List.of()).size();
        int doneCount = items.getOrDefault(ItemStatus.DONE, List.of()).size();
        model.addAttribute("newCount", newCount);
        model.addAttribute("continueCount", continueCount);
        model.addAttribute("doneCount", doneCount);
        model.addAttribute("totalCount", newCount + continueCount + doneCount);
    }

    private Map<ItemStatus, List<ReportItem>> emptyItems() {
        Map<ItemStatus, List<ReportItem>> grouped = new EnumMap<>(ItemStatus.class);
        for (ItemStatus status : ItemStatus.values()) {
            grouped.put(status, List.of());
        }
        return grouped;
    }

    private Map<ItemStatus, List<ReportItem>> completeItems(Map<ItemStatus, List<ReportItem>> items) {
        Map<ItemStatus, List<ReportItem>> grouped = new EnumMap<>(ItemStatus.class);
        for (ItemStatus status : ItemStatus.values()) {
            grouped.put(status, items == null ? List.of() : items.getOrDefault(status, List.of()));
        }
        return grouped;
    }

    private Map<Team, Map<ItemStatus, List<ReportItem>>> visibleAdminItems(String weekKey, List<Team> teams) {
        // [Q-3 fix] team 객체가 아닌 team.id 로 취합 항목을 매칭(본부장 산하 PDF 포함).
        Map<Long, Map<ItemStatus, List<ReportItem>>> itemsByTeamId = indexByTeamId(reportService.adminItems(weekKey));
        Map<Team, Map<ItemStatus, List<ReportItem>>> visible = new LinkedHashMap<>();
        for (Team team : teams) {
            TeamWeeklyStatus status = reportService.teamStatus(team.getId(), weekKey);
            if (status != null && Boolean.TRUE.equals(status.getSubmitted())) {
                visible.put(team, completeItems(itemsByTeamId.getOrDefault(team.getId(), emptyItems())));
            }
        }
        return visible;
    }

    /** [Q-3 fix] adminItems(Team 키)를 team.id 키로 재색인 — @Data equals 불일치 회피. */
    private Map<Long, Map<ItemStatus, List<ReportItem>>> indexByTeamId(Map<Team, Map<ItemStatus, List<ReportItem>>> byTeam) {
        Map<Long, Map<ItemStatus, List<ReportItem>>> byId = new HashMap<>();
        byTeam.forEach((team, grouped) -> byId.put(team.getId(), grouped));
        return byId;
    }

    private String currentPath(String active) {
        return switch (active) {
            case "team" -> "/report/team";
            case "admin" -> "/report/admin";
            case "search" -> "/report/search";
            default -> "/report/my";
        };
    }

    private String printTitle(String scope, CustomUserDetails user) {
        return switch (scope) {
            case "team" -> "팀 주간보고";
            case "admin" -> "전체 주간보고";
            default -> user.getName() + " 주간보고";
        };
    }

    private String resolveWeek(String week) {
        return WeekUtils.isValidWeekKey(week) ? week : reportService.defaultWeekKey();
    }

    private String redirectMy(String weekKey) {
        return "redirect:/report/my?week=" + weekKey;
    }

    private void runWithMessage(Runnable action, String success, RedirectAttributes ra) {
        try {
            action.run();
            ra.addFlashAttribute("message", success);
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
    }
}
