package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.AttendanceCorrectionForm;
import com.portfolio.worksystem.dto.AttendanceOverrideForm;
import com.portfolio.worksystem.dto.AttendanceSettingForm;
import com.portfolio.worksystem.dto.ApprovalDelegationForm;
import com.portfolio.worksystem.dto.BusinessTripForm;
import com.portfolio.worksystem.dto.CompanyScheduleForm;
import com.portfolio.worksystem.dto.FlexTimeRequestForm;
import com.portfolio.worksystem.dto.HolidayForm;
import com.portfolio.worksystem.dto.LeaveRequestForm;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.ApprovalDocumentSnapshot;
import com.portfolio.worksystem.model.ApprovalStep;
import com.portfolio.worksystem.model.AttendanceDailyStatus;
import com.portfolio.worksystem.model.AttendanceStatus;
import com.portfolio.worksystem.model.BusinessTrip;
import com.portfolio.worksystem.model.BusinessTripStatus;
import com.portfolio.worksystem.model.AttendanceCorrection;
import com.portfolio.worksystem.model.CompanyHoliday;
import com.portfolio.worksystem.model.CompanySchedule;
import com.portfolio.worksystem.model.FlexTimeRequest;
import com.portfolio.worksystem.model.FlexTimeStatus;
import com.portfolio.worksystem.model.LeaveRequest;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.ApprovalService;
import com.portfolio.worksystem.service.AccountService;
import com.portfolio.worksystem.service.HrService;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.WeekUtils;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.util.StringUtils;

@RequiredArgsConstructor
@Controller
public class HrController {
    private final HrService hrService;
    private final AccountService accountService;
    private final OrganizationService organizationService;
    private final ApprovalService approvalService;
    private final com.portfolio.worksystem.service.EmpGradeService empGradeService;
    private final com.portfolio.worksystem.service.LoaService loaService;
    private final com.portfolio.worksystem.service.ApprovalDocumentService approvalDocumentService;
    private final com.portfolio.worksystem.service.UserChangeLogService userChangeLogService;

    // ============================================================
    // [유지보수] 보관문서 일괄 재생성 (SUPER_ADMIN — /admin/system/** 경로 보호)
    // 본문은 유지하고 스타일 래퍼만 최신화. team 미지정 시 전체.
    // 예: POST /admin/system/approval-snapshots/regenerate?team=플랫폼개발팀
    // ============================================================
    @PostMapping("/admin/system/approval-snapshots/regenerate")
    @ResponseBody
    public Map<String, Object> regenerateApprovalSnapshots(@RequestParam(required = false) String team) {
        int regenerated = hrService.regenerateSnapshotFiles(team);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("team", (team == null || team.isBlank()) ? "(전체)" : team);
        result.put("regenerated", regenerated);
        return result;
    }

    // ============================================================
    // [N-4] 직원 HR 관리 — 휴가/시차/출장/근태 통합 직원 누계 페이지
    // ============================================================
    @GetMapping("/hr/members")
    public String members(@AuthenticationPrincipal CustomUserDetails user,
                          @RequestParam(required = false, name = "q") String query,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          @RequestParam(defaultValue = "1") int page,
                          Model model) {
        if (!hrService.canViewMemberHrOverview(user)) {
            throw new BusinessException("권한이 없습니다.");
        }
        LocalDate today = LocalDate.now();
        LocalDate fromDate = from == null ? today.withDayOfYear(1) : from;
        LocalDate toDate = to == null ? today : to;
        if (toDate.isBefore(fromDate)) toDate = fromDate;
        String keyword = StringUtils.hasText(query) ? query.trim() : "";

        int yearStart = fromDate.getYear();
        int yearEnd = toDate.getYear();

        java.util.LinkedHashMap<Long, java.util.LinkedHashMap<String, Object>> summary = new java.util.LinkedHashMap<>();

        // 휴가 (연차/반차 등 모든 휴가 종류 합계)
        for (int yr = yearStart; yr <= yearEnd; yr++) {
            for (LeaveRequest r : hrService.memberLeaveRequests(user, yr)) {
                if (r.getUserId() == null || r.getDays() == null) continue;
                if (r.getStartDate() == null) continue;
                if (r.getEndDate() != null && r.getEndDate().isBefore(fromDate)) continue;
                if (r.getStartDate().isAfter(toDate)) continue;
                if (r.getStatus() != com.portfolio.worksystem.model.LeaveStatus.PENDING
                        && r.getStatus() != com.portfolio.worksystem.model.LeaveStatus.APPROVED) continue;
                var rec = ensureMember(summary, r.getUserId(), r.getMemberName(), r.getTeamName());
                rec.put("leaveDays", ((java.math.BigDecimal) rec.get("leaveDays")).add(r.getDays()));
                rec.put("leaveCount", ((Integer) rec.get("leaveCount")) + 1);
            }
            // 시차
            for (FlexTimeRequest r : hrService.memberFlexTimeRequests(user, yr)) {
                if (r.getUserId() == null || r.getStartDate() == null) continue;
                if (r.getEndDate() != null && r.getEndDate().isBefore(fromDate)) continue;
                if (r.getStartDate().isAfter(toDate)) continue;
                if (r.getStatus() == null) continue;
                if (!"APPROVED".equals(r.getStatus().name()) && !"PENDING".equals(r.getStatus().name())) continue;
                var rec = ensureMember(summary, r.getUserId(), r.getMemberName(), r.getTeamName());
                rec.put("flexCount", ((Integer) rec.get("flexCount")) + 1);
            }
            // 출장
            for (BusinessTrip t : hrService.memberTripRequests(user, yr)) {
                if (t.getUserId() == null || t.getStartDate() == null) continue;
                if (t.getEndDate() != null && t.getEndDate().isBefore(fromDate)) continue;
                if (t.getStartDate().isAfter(toDate)) continue;
                if (t.getStatus() == null || !"APPROVED".equals(t.getStatus().name())) continue;
                var rec = ensureMember(summary, t.getUserId(), t.getMemberName(), t.getTeamName());
                rec.put("tripCount", ((Integer) rec.get("tripCount")) + 1);
                long days = t.getStartDate() != null && t.getEndDate() != null
                        ? java.time.temporal.ChronoUnit.DAYS.between(t.getStartDate(), t.getEndDate()) + 1 : 0;
                rec.put("tripDays", ((Long) rec.get("tripDays")) + days);
            }
        }
        // 근태 (선택 기간 내)
        for (AttendanceDailyStatus rec0 : hrService.memberAttendanceDailyStatuses(user, fromDate, toDate)) {
            if (rec0.getUserId() == null) continue;
            var rec = ensureMember(summary, rec0.getUserId(), rec0.getMemberName(), rec0.getTeamName());
            if (rec0.getCheckInAt() != null) rec.put("attended", ((Integer) rec.get("attended")) + 1);
            if (rec0.getStatus() == AttendanceStatus.LATE) rec.put("late", ((Integer) rec.get("late")) + 1);
            if (rec0.getStatus() == AttendanceStatus.ABSENT) rec.put("absent", ((Integer) rec.get("absent")) + 1);
        }

        var members = new java.util.ArrayList<>(summary.values());
        if (!keyword.isEmpty()) {
            String kw = keyword.toLowerCase(Locale.KOREA);
            members.removeIf(m -> {
                String n = String.valueOf(m.getOrDefault("memberName", ""));
                String t = String.valueOf(m.getOrDefault("teamName", ""));
                return !n.toLowerCase(Locale.KOREA).contains(kw)
                        && !t.toLowerCase(Locale.KOREA).contains(kw);
            });
        }
        // 이름 가나다 순 (한국어 로케일 기준)
        java.text.Collator korCollator = java.text.Collator.getInstance(java.util.Locale.KOREA);
        members.sort((a, b) -> korCollator.compare(
                String.valueOf(a.getOrDefault("memberName", "")),
                String.valueOf(b.getOrDefault("memberName", ""))));

        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, members.size());
        String encodedKeyword = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        addCommon(model, user, "직원 HR 관리", "members");
        model.addAttribute("members", com.portfolio.worksystem.model.PageInfo.slice(members, pageInfo));
        model.addAttribute("memberTotal", members.size());
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/hr/members?from=" + fromDate + "&to=" + toDate
                + "&q=" + encodedKeyword + "&page=");
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("query", keyword);
        // [B4] 기본 근무시간/특정일 예외 폼은 근태 설정(/hr/attendance/policy)으로 이전
        return "hr/members";
    }

    private java.util.LinkedHashMap<String, Object> ensureMember(
            java.util.LinkedHashMap<Long, java.util.LinkedHashMap<String, Object>> summary,
            Long userId, String memberName, String teamName) {
        return summary.computeIfAbsent(userId, k -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("userId", userId);
            m.put("memberName", memberName);
            m.put("teamName", teamName);
            m.put("leaveDays", java.math.BigDecimal.ZERO);
            m.put("leaveCount", 0);
            m.put("flexCount", 0);
            m.put("tripCount", 0);
            m.put("tripDays", 0L);
            m.put("attended", 0);
            m.put("late", 0);
            m.put("absent", 0);
            return m;
        });
    }

    @GetMapping("/hr/members/{userId}")
    public String memberDetail(@AuthenticationPrincipal CustomUserDetails user,
                               @PathVariable Long userId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(defaultValue = "1") int lpage,
                               @RequestParam(defaultValue = "1") int fpage,
                               @RequestParam(defaultValue = "1") int tpage,
                               @RequestParam(defaultValue = "1") int apage,
                               @RequestParam(required = false) String astatus,
                               @RequestParam(required = false) String edit,
                               @org.springframework.web.bind.annotation.RequestHeader(name = "X-Worksystem-Fragment", required = false) String fragment,
                               jakarta.servlet.http.HttpServletRequest request,
                               jakarta.servlet.http.HttpServletResponse response,
                               Model model) {
        response.addHeader(HttpHeaders.VARY, "X-Worksystem-Fragment");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        // [UX31] 일반 사용자의 본인 인사카드는 마이페이지로.
        // [UX45] 인사카드 열람 권한자(관리자)는 본인 카드도 인사카드 화면으로 본다.
        boolean self = userId.equals(user.getUserId());
        if (!hrService.canViewMemberHrOverview(user)) {
            if (self) return "redirect:/mypage";
            throw new BusinessException("권한이 없습니다.");
        }
        // [보안] 화면 진입 권한과 <이 대상자를 볼 권한>은 다르다.
        //  이 검사가 없으면 팀을 하나라도 이끄는 사람이 URL 의 userId 만 바꿔
        //  스코프 밖 직원의 인사카드를 열 수 있다(휴가·출장 목록은 원래 걸러지지만
        //  대상자 프로필은 그대로 렌더링됐다).
        if (!self && !hrService.canViewMemberHrCard(user, userId)) {
            throw new BusinessException("권한이 없습니다.");
        }
        LocalDate today = LocalDate.now();
        LocalDate fromDate = from == null ? today.withDayOfYear(1) : from;
        LocalDate toDate = to == null ? today : to;
        if (toDate.isBefore(fromDate)) toDate = fromDate;

        com.portfolio.worksystem.model.UserAccount target = hrService.findUser(userId);
        if (target == null) throw new BusinessException("직원을 찾을 수 없습니다.");

        // Fragment requests share the card's access checks, but do not load unrelated HR tables or edit forms.
        List<AttendanceDailyStatus> attendancesAll = (self
                ? hrService.attendanceDailyStatuses(user, fromDate, toDate)
                : hrService.memberAttendanceDailyStatuses(user, fromDate, toDate)).stream()
                .filter(a -> userId.equals(a.getUserId())).toList();
        String statusFilter = StringUtils.hasText(astatus) && !"ALL".equalsIgnoreCase(astatus) ? astatus.trim().toUpperCase(Locale.KOREA) : "";
        List<AttendanceDailyStatus> attendances = statusFilter.isEmpty()
                ? attendancesAll
                : attendancesAll.stream()
                    .filter(a -> a.getStatus() != null && statusFilter.equals(a.getStatus().name()))
                    .toList();
        int pageSize = 10;
        com.portfolio.worksystem.model.PageInfo attPage = new com.portfolio.worksystem.model.PageInfo(apage, pageSize, attendances.size());
        String memberPath = request.getContextPath() + "/hr/members/" + userId;
        String baseUrl = memberPath + "?from=" + fromDate + "&to=" + toDate + "&astatus="
                + java.net.URLEncoder.encode(statusFilter, java.nio.charset.StandardCharsets.UTF_8);
        String attendancePages = "&lpage=" + lpage + "&fpage=" + fpage + "&tpage=" + tpage;
        model.addAttribute("target", target);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("attendanceList", com.portfolio.worksystem.model.PageInfo.slice(attendances, attPage));
        model.addAttribute("attendanceTotal", attendances.size());
        model.addAttribute("attendancePageInfo", attPage);
        model.addAttribute("attendancePageBaseUrl", baseUrl + attendancePages + "&apage=");
        model.addAttribute("memberAttendanceUrl", baseUrl + attendancePages + "&apage=" + attPage.getPage());
        model.addAttribute("attendanceStatusFilter", statusFilter);
        model.addAttribute("attendanceStatuses", AttendanceStatus.values());
        model.addAttribute("attendanceFilterBaseUrl", memberPath
                + "?from=" + fromDate + "&to=" + toDate + attendancePages + "&apage=1&astatus=");
        if ("member-attendance".equals(fragment)) {
            return "hr/members-detail :: memberAttendance";
        }

        List<LeaveRequest> leaves = new java.util.ArrayList<>();
        List<FlexTimeRequest> flexes = new java.util.ArrayList<>();
        List<BusinessTrip> trips = new java.util.ArrayList<>();
        for (int yr = fromDate.getYear(); yr <= toDate.getYear(); yr++) {
            for (LeaveRequest r : (self ? hrService.ownLeaveRequests(userId, yr) : hrService.memberLeaveRequests(user, yr))) {
                if (!userId.equals(r.getUserId())) continue;
                if (r.getStartDate() == null) continue;
                if (r.getEndDate() != null && r.getEndDate().isBefore(fromDate)) continue;
                if (r.getStartDate().isAfter(toDate)) continue;
                leaves.add(r);
            }
            for (FlexTimeRequest r : (self ? hrService.ownFlexTimeRequests(userId, yr) : hrService.memberFlexTimeRequests(user, yr))) {
                if (!userId.equals(r.getUserId())) continue;
                if (r.getStartDate() == null) continue;
                if (r.getEndDate() != null && r.getEndDate().isBefore(fromDate)) continue;
                if (r.getStartDate().isAfter(toDate)) continue;
                flexes.add(r);
            }
            for (BusinessTrip t : (self ? hrService.ownBusinessTrips(userId, yr) : hrService.memberTripRequests(user, yr))) {
                if (!userId.equals(t.getUserId())) continue;
                if (t.getStartDate() == null) continue;
                if (t.getEndDate() != null && t.getEndDate().isBefore(fromDate)) continue;
                if (t.getStartDate().isAfter(toDate)) continue;
                trips.add(t);
            }
        }
        com.portfolio.worksystem.model.PageInfo leavePage = new com.portfolio.worksystem.model.PageInfo(lpage, pageSize, leaves.size());
        com.portfolio.worksystem.model.PageInfo flexPage = new com.portfolio.worksystem.model.PageInfo(fpage, pageSize, flexes.size());
        com.portfolio.worksystem.model.PageInfo tripPage = new com.portfolio.worksystem.model.PageInfo(tpage, pageSize, trips.size());

        addCommon(model, user, target.getName() + " — 인사카드", "members");
        // [UX12] 인사카드 내 [정보 수정] 모달 — user-edit 화면 흡수(조직 관리 권한)
        if (organizationService.canManageOrganization(user)) {
            model.addAttribute("editTeams", organizationService.findMembershipTeams());
            model.addAttribute("editGrades", empGradeService.selectableGrades(target.getGradeId()));
            model.addAttribute("employeeSignature", accountService.signatureFilenameForUser(userId));
            model.addAttribute("openEdit", "1".equals(edit));
        }
        // [B5] 인사카드 — 기본정보·발령 이력·연차 요약(계정관리/HR현황 이원화 해소)
        // [B7] 인사담당자는 인사카드에서 직접 휴직을 걸거나 풀 수 있다.
        model.addAttribute("loaTypes", loaService.activeTypes());
        model.addAttribute("loaHistory", loaService.ownRequests(userId, null));
        model.addAttribute("canManageHr", hrService.canManageHr(user));
        // [B9] 인사정보 변경이력 — 본인 수정·관리자 수정·직급 변경·휴직·퇴사가 모두 한 줄기로 보인다.
        model.addAttribute("changeLogs", userChangeLogService.historyForUser(userId, 200));
        model.addAttribute("changeLogTotal", userChangeLogService.countForUser(userId));
        model.addAttribute("targetRemainingLeave",
                hrService.remainingLeaveDays(userId, today.getYear()).stripTrailingZeros().toPlainString());
        model.addAttribute("leaveList", com.portfolio.worksystem.model.PageInfo.slice(leaves, leavePage));
        model.addAttribute("flexList", com.portfolio.worksystem.model.PageInfo.slice(flexes, flexPage));
        model.addAttribute("tripList", com.portfolio.worksystem.model.PageInfo.slice(trips, tripPage));
        model.addAttribute("leaveTotal", leaves.size());
        model.addAttribute("flexTotal", flexes.size());
        model.addAttribute("tripTotal", trips.size());
        model.addAttribute("leavePageInfo", leavePage);
        model.addAttribute("flexPageInfo", flexPage);
        model.addAttribute("tripPageInfo", tripPage);
        model.addAttribute("leavePageBaseUrl", baseUrl + "&fpage=" + fpage + "&tpage=" + tpage + "&apage=" + apage + "&lpage=");
        model.addAttribute("flexPageBaseUrl", baseUrl + "&lpage=" + lpage + "&tpage=" + tpage + "&apage=" + apage + "&fpage=");
        model.addAttribute("tripPageBaseUrl", baseUrl + "&lpage=" + lpage + "&fpage=" + fpage + "&apage=" + apage + "&tpage=");
        return "hr/members-detail";
    }

    /** [UX17] 통합 화면으로 리다이렉트 — 휴가 목록은 휴가·근태 화면의 [휴가] 탭. */
    @GetMapping("/hr/leave")
    public String leave(@RequestParam(required = false) Integer year, @RequestParam(required = false, name = "q") String query) {
        return "redirect:/hr/attendance?tab=leave"
                + (query != null ? "&lq=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) : "");
    }

    @GetMapping("/hr/leave/new")
    public String newLeave(@AuthenticationPrincipal CustomUserDetails user,
                           @RequestParam(required = false) Integer year,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                           Model model) {
        int targetYear = year == null ? hrService.currentYear() : year;
        // [UX40] 근태 정정 화면에서 '휴가로 처리' 진입 — 대상 일자 프리필
        model.addAttribute("prefillStart", start);
        model.addAttribute("prefillEnd", end == null ? start : end);
        addCommon(model, user, "휴가 신청", "leaveNew");
        model.addAttribute("year", targetYear);
        model.addAttribute("balance", hrService.leaveBalance(user, targetYear));
        model.addAttribute("leaveEntitlementSummary", hrService.leaveEntitlementSummary(user, targetYear));
        // [C2] 휴가 종류 — DB 정의(사용 중지 종류 제외, 라벨·증빙 표기 반영)
        model.addAttribute("leaveTypes", hrService.activeLeaveTypeDefs());
        // [A8] 우리 팀 부재 현황(이번 달~다음 달) — 신청 일자 판단 참고
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        model.addAttribute("teamAbsences", hrService.teamAbsences(user.getUserId(),
                monthStart, monthStart.plusMonths(2).minusDays(1)));
        addApprovalRouteModel(model, user, ApprovalDocumentType.LEAVE, null, null);
        return "hr/leave-new";
    }

    /** 휴가 신청 폼의 차감 예정일수 실시간 미리보기(AJAX). */
    @GetMapping("/hr/leave/preview-days")
    @ResponseBody
    public Map<String, Object> leavePreviewDays(@AuthenticationPrincipal CustomUserDetails user,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) LocalDate startDate,
                                                @RequestParam(required = false) LocalDate endDate) {
        HrService.LeaveDaysPreview preview = hrService.leaveDaysPreview(user, type, startDate, endDate);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", preview.days());
        body.put("annualCounted", preview.annualCounted());
        body.put("remaining", preview.remaining());
        body.put("remainingAfter", preview.remainingAfter());
        body.put("warnings", preview.warnings());
        // 신청 날짜의 연도 기준값 — 상단 카드가 올해에 머물러 있지 않도록 함께 내려 준다.
        body.put("year", preview.year());
        body.put("entitlement", preview.entitlement());
        body.put("accrualNote", preview.accrualNote());
        return body;
    }

    @PostMapping("/hr/leave/request")
    public String requestLeave(@AuthenticationPrincipal CustomUserDetails user,
                               @ModelAttribute LeaveRequestForm form,
                               RedirectAttributes ra) {
        runWithMessage(() -> hrService.requestLeave(user, form), "연차 신청이 등록되었습니다.", ra);
        addDelegationPromptIfNeeded(user, form.startDate(), form.endDate(), "휴가", ra);
        return "redirect:/hr/attendance?tab=leave";
    }

    /** [UX41] 신청 기간에 내 대리승인 지정이 없으면 지정 유도 프롬프트(성공 시에만). */
    private void addDelegationPromptIfNeeded(CustomUserDetails user, LocalDate start, LocalDate end,
                                             String kind, RedirectAttributes ra) {
        if (ra.getFlashAttributes().containsKey("error")) return;
        if (start == null || end == null) return;
        if (approvalService.hasDelegationCovering(user.getUserId(), start, end)) return;
        java.util.Map<String, String> prompt = new java.util.LinkedHashMap<>();
        prompt.put("kind", kind);
        prompt.put("start", start.toString());
        prompt.put("end", end.toString());
        ra.addFlashAttribute("delegationPrompt", prompt);
    }

    @PostMapping("/hr/leave/{id}/approve")
    public String approveLeave(@AuthenticationPrincipal CustomUserDetails user,
                               @PathVariable Long id,
                               @RequestParam(required = false) String password,
                               @RequestParam(required = false, name = "back") String backParam,
                               RedirectAttributes ra) {
        // [B2] 결재함 인라인 처리 시 back으로 복귀 (기본 = 문서 상세)
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.LEAVE, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.approveLeave(user, id), "연차 신청을 승인했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/leave/{id}/reject")
    public String rejectLeave(@AuthenticationPrincipal CustomUserDetails user,
                              @PathVariable Long id,
                              @RequestParam(required = false) String reason,
                              @RequestParam(required = false) String password,
                              @RequestParam(required = false, name = "back") String backParam,
                              RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.LEAVE, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.rejectLeave(user, id, reason), "연차 신청을 반려했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/leave/{id}/cancel")
    public String cancelLeave(@AuthenticationPrincipal CustomUserDetails user,
                              @PathVariable Long id,
                              @RequestParam(required = false) String back,
                              RedirectAttributes ra) {
        runWithMessage(() -> hrService.cancelLeave(user, id), "연차 신청을 회수했습니다.", ra);
        return cancelRedirect(back, "/hr/attendance?tab=leave");
    }

    @GetMapping("/hr/leave/{id}/cancel-request/preview")
    public String leaveCancelPreview(@AuthenticationPrincipal CustomUserDetails user,
                                     @PathVariable Long id, Model model) {
        HrService.LeaveCancelPreview preview = hrService.leaveCancelPreview(user, id);
        com.portfolio.worksystem.model.LeaveRequest r = preview.request();
        model.addAttribute("steps", preview.steps());
        model.addAttribute("applicantName", r.getMemberName());
        model.addAttribute("applicantSignature", accountService.signatureFilenameForUser(r.getUserId()));
        model.addAttribute("memberName", r.getMemberName());
        model.addAttribute("teamName", r.getTeamName());
        model.addAttribute("typeLabel", r.getType() != null ? r.getTypeLabel() : "");
        model.addAttribute("periodText", r.getStartDate() + " ~ " + r.getEndDate());
        model.addAttribute("amountText", r.getDays() + "일");
        model.addAttribute("reason", r.getReason());
        return "hr/leave-cancel-preview";
    }

    @PostMapping("/hr/leave/{id}/cancel-request")
    public String requestLeaveCancel(@AuthenticationPrincipal CustomUserDetails user,
                                     @PathVariable Long id,
                                     @RequestParam(required = false) String reason,
                                     @RequestParam(required = false) String back,
                                     RedirectAttributes ra) {
        runWithMessage(() -> hrService.requestLeaveCancel(user, id, reason), "연차 취소 결재가 시작되었습니다.", ra);
        return cancelRedirect(back, docDetailUrl(ApprovalDocumentType.LEAVE, id));
    }

    @PostMapping("/hr/leave/{id}/cancel-withdraw")
    public String withdrawLeaveCancel(@AuthenticationPrincipal CustomUserDetails user,
                                      @PathVariable Long id,
                                      @RequestParam(required = false) String back,
                                      RedirectAttributes ra) {
        runWithMessage(() -> hrService.withdrawLeaveCancel(user, id), "취소 결재를 회수했습니다.", ra);
        return cancelRedirect(back, docDetailUrl(ApprovalDocumentType.LEAVE, id));
    }

    @GetMapping("/hr/leave/{id}/document")
    public String leaveDocument(@AuthenticationPrincipal CustomUserDetails user,
                                @PathVariable Long id, Model model) {
        com.portfolio.worksystem.model.LeaveRequest r = hrService.leaveDocument(user, id);
        if (isSnapshotStatus(r.getStatus() != null ? r.getStatus().name() : null)) {
            return renderDocumentSnapshot(user, ApprovalDocumentType.LEAVE, id, model);
        }
        List<ApprovalStep> steps = hrService.leaveApprovalSteps(id);
        model.addAttribute("title", "휴가 신청서");
        model.addAttribute("docKind", "휴가 신청서");
        model.addAttribute("memberName", r.getMemberName());
        model.addAttribute("teamName", r.getTeamName());
        model.addAttribute("typeLabel", r.getType() != null ? r.getTypeLabel() : "");
        model.addAttribute("periodText", r.getStartDate() + " ~ " + r.getEndDate());
        model.addAttribute("amountText", r.getDays() + "일");
        model.addAttribute("reason", r.getReason());
        model.addAttribute("cancelReason",
                (r.getStatus() == com.portfolio.worksystem.model.LeaveStatus.CANCEL_PENDING
                        || r.getStatus() == com.portfolio.worksystem.model.LeaveStatus.CANCEL_APPROVED)
                        ? r.getCancelReason() : null);
        model.addAttribute("statusLabel", r.getStatus() != null ? r.getStatus().getLabel() : "");
        model.addAttribute("createdAt", r.getCreatedAt());
        model.addAttribute("steps", steps);
        model.addAttribute("stepSignatures", signatureMap(steps));
        model.addAttribute("applicantName", r.getMemberName());
        model.addAttribute("applicantSignature", accountService.signatureFilenameForUser(r.getUserId()));
        // [A8] 신청 기간 팀 부재자 — 결재 판단 참고
        model.addAttribute("teamAbsences", hrService.teamAbsences(r.getUserId(), r.getStartDate(), r.getEndDate()));
        addDocActionModel(model, user, ApprovalDocumentType.LEAVE, id,
                r.getStatus() != null ? r.getStatus().name() : null);
        return "hr/approval-document";
    }

    private void addDocActionModel(Model model, CustomUserDetails user,
                                   ApprovalDocumentType type, Long docId, String statusName) {
        ApprovalStep pending = approvalService.pendingStep(type, docId);
        boolean actionable = "PENDING".equals(statusName) && pending != null
                && (java.util.Objects.equals(pending.getApproverUserId(), user.getUserId())
                || java.util.Objects.equals(pending.getDelegateUserId(), user.getUserId()));
        model.addAttribute("documentTypeName", type.name());
        model.addAttribute("documentId", docId);
        model.addAttribute("actionable", actionable);
    }

    private boolean isSnapshotStatus(String statusName) {
        return "APPROVED".equals(statusName) || "REJECTED".equals(statusName);
    }

    private String renderDocumentSnapshot(CustomUserDetails user, ApprovalDocumentType type, Long id, Model model) {
        ApprovalDocumentSnapshot snapshot = hrService.approvalDocumentSnapshot(user, type, id);
        model.addAttribute("title", snapshot.getDocumentKind());
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("downloadUrl", "/hr/documents/" + type.name() + "/" + id + "/snapshot/download");
        model.addAttribute("documentTypeName", type.name());
        model.addAttribute("documentId", id);
        model.addAttribute("postActionable", approvalService.canPostApproveStep(type, id, user));
        return "hr/document-snapshot";
    }

    private String docDetailUrl(ApprovalDocumentType type, Long id) {
        return "redirect:" + docPath(type, id);
    }

    private boolean passwordOk(CustomUserDetails user, String password, RedirectAttributes ra) {
        if (!accountService.matchesPassword(user.getUserId(), password)) {
            ra.addFlashAttribute("error", "로그인 비밀번호가 일치하지 않아 결재를 처리하지 못했습니다.");
            return false;
        }
        return true;
    }

    private String cancelRedirect(String back, String fallback) {
        return "redirect:" + (back != null && back.startsWith("/hr/") ? back : fallback);
    }

    @GetMapping("/hr/flex-time/{id}/document")
    public String flexTimeDocument(@AuthenticationPrincipal CustomUserDetails user,
                                   @PathVariable Long id, Model model) {
        FlexTimeRequest f = hrService.flexTimeDocument(user, id);
        if (isSnapshotStatus(f.getStatus() != null ? f.getStatus().name() : null)) {
            return renderDocumentSnapshot(user, ApprovalDocumentType.FLEX_TIME, id, model);
        }
        List<ApprovalStep> steps = hrService.flexTimeApprovalSteps(id);
        model.addAttribute("title", "시차출근 신청서");
        model.addAttribute("docKind", "시차출근 신청서");
        model.addAttribute("memberName", f.getMemberName());
        model.addAttribute("teamName", f.getTeamName());
        model.addAttribute("typeLabel", f.getShiftGroupDisplay());
        model.addAttribute("periodText", f.getStartDate() + " ~ " + f.getEndDate());
        model.addAttribute("amountText", f.getShiftGroup() != null ? f.getShiftGroupDisplay() + " 시차출근" : "-");
        model.addAttribute("reason", f.getReason());
        model.addAttribute("statusLabel", f.getStatus() != null ? f.getStatus().getLabel() : "");
        model.addAttribute("createdAt", f.getCreatedAt());
        model.addAttribute("steps", steps);
        model.addAttribute("stepSignatures", signatureMap(steps));
        model.addAttribute("applicantName", f.getMemberName());
        model.addAttribute("applicantSignature", accountService.signatureFilenameForUser(f.getUserId()));
        addDocActionModel(model, user, ApprovalDocumentType.FLEX_TIME, id,
                f.getStatus() != null ? f.getStatus().name() : null);
        return "hr/approval-document";
    }

    @GetMapping("/hr/loa/{id}/document")
    public String loaDocument(@AuthenticationPrincipal CustomUserDetails user,
                              @PathVariable Long id, Model model) {
        com.portfolio.worksystem.model.LoaRequest r = approvalDocumentService.loaDocument(user, id);
        if (isSnapshotStatus(r.getStatus() != null ? r.getStatus().name() : null)) {
            return renderDocumentSnapshot(user, ApprovalDocumentType.LOA, id, model);
        }
        List<ApprovalStep> steps = approvalService.steps(ApprovalDocumentType.LOA, id);
        model.addAttribute("title", "휴직 신청서");
        model.addAttribute("docKind", "휴직 신청서");
        model.addAttribute("memberName", r.getMemberName());
        model.addAttribute("teamName", r.getTeamName());
        model.addAttribute("typeLabel", r.getTypeLabel());
        model.addAttribute("periodText", r.getPeriodText());
        model.addAttribute("amountText", "EXCLUDED".equals(r.getAccrualTreatment())
                ? "연차 비례 삭감" : "연차 영향 없음(출근 간주)");
        model.addAttribute("reason", r.getReason());
        model.addAttribute("statusLabel", r.getStatus() != null ? r.getStatus().getLabel() : "");
        model.addAttribute("createdAt", r.getCreatedAt());
        model.addAttribute("steps", steps);
        model.addAttribute("stepSignatures", signatureMap(steps));
        model.addAttribute("applicantName", r.getMemberName());
        model.addAttribute("applicantSignature", accountService.signatureFilenameForUser(r.getUserId()));
        addDocActionModel(model, user, ApprovalDocumentType.LOA, id,
                r.getStatus() != null ? r.getStatus().name() : null);
        return "hr/approval-document";
    }

    @GetMapping("/hr/attendance/correction/{id}/document")
    public String attendanceCorrectionDocument(@AuthenticationPrincipal CustomUserDetails user,
                                                @PathVariable Long id, Model model) {
        AttendanceCorrection c = hrService.attendanceCorrectionDocument(user, id);
        if (isSnapshotStatus(c.getStatus() != null ? c.getStatus().name() : null)) {
            return renderDocumentSnapshot(user, ApprovalDocumentType.ATTENDANCE_CORRECTION, id, model);
        }
        List<ApprovalStep> steps = hrService.attendanceCorrectionApprovalSteps(id);
        model.addAttribute("title", "근태 정정 신청서");
        model.addAttribute("docKind", "근태 정정 신청서");
        model.addAttribute("memberName", c.getMemberName());
        model.addAttribute("teamName", c.getTeamName());
        model.addAttribute("typeLabel", "근태 정정");
        model.addAttribute("periodText", String.valueOf(c.getWorkDate()));
        model.addAttribute("amountText",
                "정정 출근 " + (c.getRequestedCheckIn() != null ? c.getRequestedCheckIn() : "-")
                        + " / 정정 퇴근 " + (c.getRequestedCheckOut() != null ? c.getRequestedCheckOut() : "-"));
        model.addAttribute("reason", c.getReason());
        model.addAttribute("statusLabel", c.getStatus() != null ? c.getStatus().getLabel() : "");
        model.addAttribute("createdAt", c.getCreatedAt());
        model.addAttribute("steps", steps);
        model.addAttribute("stepSignatures", signatureMap(steps));
        model.addAttribute("applicantName", c.getMemberName());
        model.addAttribute("applicantSignature", accountService.signatureFilenameForUser(c.getUserId()));
        addDocActionModel(model, user, ApprovalDocumentType.ATTENDANCE_CORRECTION, id,
                c.getStatus() != null ? c.getStatus().name() : null);
        return "hr/approval-document";
    }

    @GetMapping("/hr/trips/{id}/document")
    public String tripDocument(@AuthenticationPrincipal CustomUserDetails user,
                               @PathVariable Long id, Model model) {
        BusinessTrip t = hrService.tripDocument(user, id);
        if (isSnapshotStatus(t.getStatus() != null ? t.getStatus().name() : null)) {
            return renderDocumentSnapshot(user, ApprovalDocumentType.TRIP, id, model);
        }
        List<ApprovalStep> steps = hrService.tripApprovalSteps(id);
        model.addAttribute("title", "출장 신청서");
        model.addAttribute("docKind", "출장 신청서");
        model.addAttribute("memberName", t.getMemberName());
        model.addAttribute("teamName", t.getTeamName());
        model.addAttribute("typeLabel", t.getDestination());
        model.addAttribute("periodText", t.getStartDate() + " ~ " + t.getEndDate());
        model.addAttribute("amountText", t.getPurpose());
        model.addAttribute("reason", t.getPurpose());
        model.addAttribute("statusLabel", t.getStatus() != null ? t.getStatus().name() : "");
        model.addAttribute("createdAt", t.getCreatedAt());
        model.addAttribute("steps", steps);
        model.addAttribute("stepSignatures", signatureMap(steps));
        model.addAttribute("applicantName", t.getMemberName());
        model.addAttribute("applicantSignature", accountService.signatureFilenameForUser(t.getUserId()));
        addDocActionModel(model, user, ApprovalDocumentType.TRIP, id,
                t.getStatus() != null ? t.getStatus().name() : null);
        return "hr/approval-document";
    }

    private Map<Long, String> signatureMap(List<ApprovalStep> steps) {
        Map<Long, String> map = new java.util.HashMap<>();
        for (ApprovalStep s : steps) {
            if (s.getActedBy() != null && !map.containsKey(s.getActedBy())) {
                map.put(s.getActedBy(), accountService.signatureFilenameForUser(s.getActedBy()));
            }
        }
        return map;
    }

    /** [UX17] 통합 화면으로 리다이렉트 — 시차출근 목록은 [시차출근] 탭. */
    @GetMapping("/hr/flex-time")
    public String flexTime(@RequestParam(required = false, name = "q") String query) {
        return "redirect:/hr/attendance?tab=flex"
                + (query != null ? "&fq=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) : "");
    }

    @GetMapping("/hr/flex-time/new")
    public String newFlexTime(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "시차출근 신청", "flexTimeNew");
        model.addAttribute("shiftGroups", hrService.activeShiftGroups());
        addApprovalRouteModel(model, user, ApprovalDocumentType.FLEX_TIME, null, null);
        return "hr/flex-time-new";
    }

    @PostMapping("/hr/flex-time/request")
    public String requestFlexTime(@AuthenticationPrincipal CustomUserDetails user,
                                  @ModelAttribute FlexTimeRequestForm form,
                                  RedirectAttributes ra) {
        runWithMessage(() -> hrService.requestFlexTime(user, form), "시차출근 신청이 등록되었습니다.", ra);
        return "redirect:/hr/attendance?tab=flex";
    }

    @PostMapping("/hr/flex-time/{id}/approve")
    public String approveFlexTime(@AuthenticationPrincipal CustomUserDetails user,
                                  @PathVariable Long id,
                                  @RequestParam(required = false) String password,
                                  @RequestParam(required = false, name = "back") String backParam,
                                  RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.FLEX_TIME, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.approveFlexTime(user, id), "시차출근 신청을 승인했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/flex-time/{id}/reject")
    public String rejectFlexTime(@AuthenticationPrincipal CustomUserDetails user,
                                 @PathVariable Long id,
                                 @RequestParam(required = false) String reason,
                                 @RequestParam(required = false) String password,
                                 @RequestParam(required = false, name = "back") String backParam,
                                 RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.FLEX_TIME, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.rejectFlexTime(user, id, reason), "시차출근 신청을 반려했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/flex-time/{id}/cancel")
    public String cancelFlexTime(@AuthenticationPrincipal CustomUserDetails user,
                                 @PathVariable Long id,
                                 @RequestParam(required = false) String back,
                                 RedirectAttributes ra) {
        runWithMessage(() -> hrService.cancelFlexTime(user, id), "시차출근 신청을 회수했습니다.", ra);
        return cancelRedirect(back, "/hr/attendance?tab=flex");
    }

    /** [B8] 인사카드에서 퇴사 처리 — 퇴사일을 비우면 취소다. */
    @PostMapping("/hr/members/{userId}/resign")
    public String resignMember(@AuthenticationPrincipal CustomUserDetails user,
                               @PathVariable Long userId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate resignedOn,
                               @RequestParam(defaultValue = "false") boolean deactivate,
                               RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message", loaService.resign(user, userId, resignedOn, deactivate));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/hr/members/" + userId;
    }

    /* ── [B7] 휴직 ─────────────────────────────────────── */

    @GetMapping("/hr/loa")
    public String loaList(@AuthenticationPrincipal CustomUserDetails user,
                          @RequestParam(required = false) Integer year,
                          Model model) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        addCommon(model, user, "휴직", "loa");
        model.addAttribute("loaYear", targetYear);
        model.addAttribute("currentYear", LocalDate.now().getYear());
        model.addAttribute("myRequests", loaService.ownRequests(user.getUserId(), targetYear));
        // 인사담당자는 전원, 팀장·본부장은 산하만 본다.
        model.addAttribute("memberRequests", hrService.canManageHr(user)
                ? loaService.allRequests(user, targetYear)
                : loaService.memberRequests(user, targetYear));
        model.addAttribute("canManageHr", hrService.canManageHr(user));
        return "hr/loa";
    }

    @GetMapping("/hr/loa/new")
    public String newLoa(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "휴직 신청", "loaNew");
        model.addAttribute("loaTypes", loaService.activeTypes());
        addApprovalRouteModel(model, user, ApprovalDocumentType.LOA, null, null);
        return "hr/loa-new";
    }

    @PostMapping("/hr/loa/request")
    public String requestLoa(@AuthenticationPrincipal CustomUserDetails user,
                             @RequestParam String type,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                             @RequestParam(required = false) String reason,
                             RedirectAttributes ra) {
        runWithMessage(() -> loaService.requestLoa(user, type, startDate, endDate, reason),
                "휴직 신청이 등록되었습니다.", ra);
        return "redirect:/hr/loa";
    }

    @PostMapping("/hr/loa/{id}/approve")
    public String approveLoa(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             @RequestParam(required = false) String password,
                             @RequestParam(required = false, name = "back") String backParam,
                             RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.LOA, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> loaService.approve(user, id), "휴직 신청을 승인했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/loa/{id}/reject")
    public String rejectLoa(@AuthenticationPrincipal CustomUserDetails user,
                            @PathVariable Long id,
                            @RequestParam(required = false) String reason,
                            @RequestParam(required = false) String password,
                            @RequestParam(required = false, name = "back") String backParam,
                            RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.LOA, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> loaService.reject(user, id, reason), "휴직 신청을 반려했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/loa/{id}/cancel")
    public String cancelLoa(@AuthenticationPrincipal CustomUserDetails user,
                            @PathVariable Long id,
                            @RequestParam(required = false) String back,
                            RedirectAttributes ra) {
        runWithMessage(() -> loaService.cancel(user, id), "휴직 신청을 회수했습니다.", ra);
        return cancelRedirect(back, "/hr/loa");
    }

    /**
     * [B7] 인사카드에서 인사담당자가 직접 휴직을 걸거나 푼다 — 결재를 거치지 않는다.
     * 시작일을 비우면 복직 처리다.
     */
    @PostMapping("/hr/members/{userId}/loa")
    public String setMemberLoa(@AuthenticationPrincipal CustomUserDetails user,
                               @PathVariable Long userId,
                               @RequestParam(required = false) String type,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                               RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message", loaService.setLeaveOfAbsence(user, userId, type, startDate, endDate));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/hr/members/" + userId;
    }

    @GetMapping("/hr/approvals")
    public String approvalInbox(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        return "redirect:/hr/documents?box=pending";
    }

    @GetMapping("/hr/documents")
    public String documents(@AuthenticationPrincipal CustomUserDetails user,
                            @RequestParam(defaultValue = "pending") String box,
                            @RequestParam(required = false, name = "q") String query,
                            @RequestParam(required = false) String type,
                            @RequestParam(defaultValue = "1") int page,
                            Model model) {
        String normalizedBox = normalizeDocumentBox(box);
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        String typeFilter = StringUtils.hasText(type) && !"ALL".equals(type) ? type.trim() : "";
        String group = documentGroup(normalizedBox);

        List<com.portfolio.worksystem.model.ApprovalInboxItem> rawDocs;
        if ("all".equals(normalizedBox)) {
            // 결재 문서함 전체 = pending(내 차례) + requested(내가 올림) + completed(완료) + after(후결)
            var map = new java.util.LinkedHashMap<String, com.portfolio.worksystem.model.ApprovalInboxItem>();
            for (var it : approvalService.inbox(user)) {
                map.put(it.getDocumentType() + ":" + it.getDocumentId(), it);
            }
            for (String b : new String[]{"requested", "completed", "after"}) {
                for (var it : approvalService.documents(user, b)) {
                    map.putIfAbsent(it.getDocumentType() + ":" + it.getDocumentId(), it);
                }
            }
            rawDocs = new java.util.ArrayList<>(map.values());
        } else if ("ioall".equals(normalizedBox)) {
            var map = new java.util.LinkedHashMap<String, com.portfolio.worksystem.model.ApprovalInboxItem>();
            for (String b : new String[]{"sent", "received"}) {
                for (var it : approvalService.documents(user, b)) {
                    map.putIfAbsent(it.getDocumentType() + ":" + it.getDocumentId(), it);
                }
            }
            rawDocs = new java.util.ArrayList<>(map.values());
        } else if ("pending".equals(normalizedBox)) {
            rawDocs = approvalService.inbox(user);
        } else {
            rawDocs = approvalService.documents(user, normalizedBox);
        }
        List<com.portfolio.worksystem.model.ApprovalInboxItem> docs = rawDocs.stream()
                .filter(d -> typeFilter.isEmpty()
                        || (d.getDocumentType() != null && typeFilter.equals(d.getDocumentType().name())))
                .filter(d -> keyword.isEmpty() || containsIgnoreCase(d.getRequesterName(), keyword)
                        || containsIgnoreCase(d.getTitle(), keyword)
                        || containsIgnoreCase(d.getTeamName(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, docs.size());
        String encodedKeyword = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        addCommon(model, user, documentBoxTitle(normalizedBox), "io".equals(group) ? "documentsIo" : "documents");
        List<com.portfolio.worksystem.model.ApprovalInboxItem> pagedItems =
                com.portfolio.worksystem.model.PageInfo.slice(docs, pageInfo);
        // [B2] 결재 대기 휴가 건 판단 정보 — 신청자 잔여 연차 + 기간 팀 부재 인원(현재 페이지만 계산)
        if ("pending".equals(normalizedBox)) {
            java.util.Map<Long, String> leaveJudgeInfo = new java.util.HashMap<>();
            for (com.portfolio.worksystem.model.ApprovalInboxItem item : pagedItems) {
                if (item.getDocumentType() != ApprovalDocumentType.LEAVE
                        || item.getRequesterId() == null || item.getStartDate() == null) {
                    continue;
                }
                java.math.BigDecimal remaining =
                        hrService.remainingLeaveDays(item.getRequesterId(), item.getStartDate().getYear());
                int absentCount = hrService.teamAbsences(item.getRequesterId(),
                        item.getStartDate(), item.getEndDate()).size();
                String info = "잔여 " + remaining.stripTrailingZeros().toPlainString() + "일";
                if (absentCount > 0) {
                    info += " · 기간 팀 부재 " + absentCount + "명";
                }
                leaveJudgeInfo.put(item.getDocumentId(), info);
            }
            model.addAttribute("leaveJudgeInfo", leaveJudgeInfo);
        }
        model.addAttribute("items", pagedItems);
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/hr/documents?box=" + normalizedBox
                + "&q=" + encodedKeyword + "&type=" + typeFilter + "&page=");
        model.addAttribute("documentBox", normalizedBox);
        model.addAttribute("documentBoxTitle", documentBoxTitle(normalizedBox));
        model.addAttribute("documentBoxes", "io".equals(group) ? ioBoxes() : documentBoxes());
        model.addAttribute("documentGroup", group);
        model.addAttribute("query", keyword);
        model.addAttribute("typeFilter", typeFilter);
        model.addAttribute("documentTypes", ApprovalDocumentType.values());
        return "hr/document-archive";
    }

    @GetMapping("/hr/documents/{type}/{id}/snapshot")
    public String documentSnapshot(@AuthenticationPrincipal CustomUserDetails user,
                                   @PathVariable ApprovalDocumentType type,
                                   @PathVariable Long id,
                                   Model model) {
        if (!shouldRenderSnapshot(user, type, id)) {
            return "redirect:" + docPath(type, id);
        }
        return renderDocumentSnapshot(user, type, id, model);
    }

    @GetMapping("/hr/documents/{type}/{id}/snapshot/download")
    public ResponseEntity<Resource> downloadDocumentSnapshot(@AuthenticationPrincipal CustomUserDetails user,
                                                             @PathVariable ApprovalDocumentType type,
                                                             @PathVariable Long id) {
        if (!shouldRenderSnapshot(user, type, id)) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .contentType(org.springframework.http.MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body(null);
        }
        com.portfolio.worksystem.service.ApprovalDocumentService.StoredApprovalDocument document = hrService.loadApprovalDocumentSnapshotFile(user, type, id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(document.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(document.originalFilename(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(document.resource());
    }

    @PostMapping("/hr/documents/{type}/{id}/post-approve")
    public String postApproveDocument(@AuthenticationPrincipal CustomUserDetails user,
                                      @PathVariable ApprovalDocumentType type,
                                      @PathVariable Long id,
                                      @RequestParam(required = false) String password,
                                      @RequestParam(required = false) String back,
                                      RedirectAttributes ra) {
        if (!passwordOk(user, password, ra)) {
            return postApproveRedirect(back, type, id);
        }
        runWithMessage(() -> hrService.postApproveDocument(user, type, id), "후결 처리했습니다.", ra);
        return postApproveRedirect(back, type, id);
    }

    private String postApproveRedirect(String back, ApprovalDocumentType type, Long id) {
        if (StringUtils.hasText(back) && (back.startsWith("/hr/documents?box=after") || back.equals(docPath(type, id)))) {
            return "redirect:" + back;
        }
        return "redirect:" + docPath(type, id);
    }

    @GetMapping("/api/hr/approval-route/warning")
    public ResponseEntity<Map<String, String>> approvalRouteWarning(@AuthenticationPrincipal CustomUserDetails user,
                                                                    @RequestParam ApprovalDocumentType type,
                                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        String warning = hrService.approvalRouteWarning(user, type, startDate, endDate);
        return ResponseEntity.ok(Map.of("warning", warning == null ? "" : warning));
    }

    private String docPath(ApprovalDocumentType type, Long id) {
        return switch (type) {
            case LEAVE, LEAVE_CANCEL -> "/hr/leave/" + id + "/document";
            case TRIP -> "/hr/trips/" + id + "/document";
            case TRIP_REPORT -> "/hr/trips/report/" + id + "/document";
            case FLEX_TIME -> "/hr/flex-time/" + id + "/document";
            case ATTENDANCE_CORRECTION -> "/hr/attendance/correction/" + id + "/document";
            case LOA -> "/hr/loa/" + id + "/document";
        };
    }

    private boolean shouldRenderSnapshot(CustomUserDetails user, ApprovalDocumentType type, Long id) {
        String statusName = switch (type) {
            case LOA -> {
                com.portfolio.worksystem.model.LoaRequest loa = loaService.request(id);
                yield loa.getStatus() != null ? loa.getStatus().name() : null;
            }
            case LEAVE, LEAVE_CANCEL -> {
                LeaveRequest request = hrService.leaveDocument(user, id);
                yield request.getStatus() != null ? request.getStatus().name() : null;
            }
            case TRIP -> {
                BusinessTrip trip = hrService.tripDocument(user, id);
                yield trip.getStatus() != null ? trip.getStatus().name() : null;
            }
            case TRIP_REPORT -> {
                com.portfolio.worksystem.model.BusinessTripReport report = hrService.tripReportDocument(user, id);
                yield report.getStatus() != null ? report.getStatus().name() : null;
            }
            case FLEX_TIME -> {
                FlexTimeRequest request = hrService.flexTimeDocument(user, id);
                yield request.getStatus() != null ? request.getStatus().name() : null;
            }
            case ATTENDANCE_CORRECTION -> {
                AttendanceCorrection correction = hrService.attendanceCorrectionDocument(user, id);
                yield correction.getStatus() != null ? correction.getStatus().name() : null;
            }
        };
        return isSnapshotStatus(statusName);
    }

    @GetMapping("/hr/schedules")
    public String schedules(@AuthenticationPrincipal CustomUserDetails user,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false, name = "q") String query,
                            @RequestParam(required = false, name = "hq") String holidayQuery,
                            @RequestParam(required = false) Integer calYear,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "1") int hpage,
                            Model model) {
        LocalDate today = LocalDate.now();
        LocalDate fromDate = from == null ? today.withDayOfMonth(1) : from;
        LocalDate toDate = to == null ? fromDate.plusMonths(1).minusDays(1) : to;
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        String holidayKeyword = StringUtils.hasText(holidayQuery) ? holidayQuery.trim() : "";
        List<CompanySchedule> schedules = hrService.companySchedules(fromDate, toDate).stream()
                .filter(schedule -> keyword.isEmpty()
                        || containsIgnoreCase(schedule.getTitle(), keyword)
                        || containsIgnoreCase(schedule.getDescription(), keyword))
                .toList();
        List<CompanyHoliday> holidays = hrService.companyHolidays(fromDate, toDate).stream()
                .filter(holiday -> holidayKeyword.isEmpty()
                        || containsIgnoreCase(holiday.getName(), holidayKeyword)
                        || containsIgnoreCase(holiday.getCreatedByName(), holidayKeyword)
                        || (holiday.getHolidayDate() != null && containsIgnoreCase(holiday.getHolidayDate().toString(), holidayKeyword)))
                .toList();
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, schedules.size());
        com.portfolio.worksystem.model.PageInfo holidayPageInfo = new com.portfolio.worksystem.model.PageInfo(hpage, 20, holidays.size());
        String encQ = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        String encHq = java.net.URLEncoder.encode(holidayKeyword, java.nio.charset.StandardCharsets.UTF_8);
        addCommon(model, user, "일정관리", "schedules");
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("query", keyword);
        model.addAttribute("holidayQuery", holidayKeyword);
        model.addAttribute("schedules", com.portfolio.worksystem.model.PageInfo.slice(schedules, pageInfo));
        model.addAttribute("holidays", com.portfolio.worksystem.model.PageInfo.slice(holidays, holidayPageInfo));
        model.addAttribute("scheduleTotal", schedules.size());
        model.addAttribute("holidayTotal", holidays.size());
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("holidayPageInfo", holidayPageInfo);
        model.addAttribute("pageBaseUrl", "/hr/schedules?from=" + fromDate + "&to=" + toDate
                + "&q=" + encQ + "&hq=" + encHq + "&hpage=" + hpage + "&page=");
        model.addAttribute("holidayPageBaseUrl", "/hr/schedules?from=" + fromDate + "&to=" + toDate
                + "&q=" + encQ + "&hq=" + encHq + "&page=" + page + "&hpage=");
        // [B3] 휴무일 연간 캘린더 (법정=적색, 회사=황색)
        int calendarYear = calYear == null ? today.getYear() : calYear;
        model.addAttribute("calYear", calendarYear);
        model.addAttribute("holidayCalendar", buildHolidayCalendar(calendarYear));
        return "hr/schedules";
    }

    /** [A3] 관리자 직원×일자 근태 매트릭스. */
    @GetMapping("/hr/members/matrix")
    public String membersMatrix(@AuthenticationPrincipal CustomUserDetails user,
                                @RequestParam(required = false) String month,
                                @RequestParam(required = false) Long teamId,
                                @RequestParam(required = false) String lockYm,
                                @RequestParam(required = false, name = "q") String query,
                                Model model) {
        if (!hrService.canViewMemberHrOverview(user)) {
            throw new BusinessException("직원 근태 매트릭스는 조직 관리자만 볼 수 있습니다.");
        }
        java.time.YearMonth ym;
        try {
            ym = month == null ? java.time.YearMonth.now() : java.time.YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            ym = java.time.YearMonth.now();
        }
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        List<AttendanceDailyStatus> statuses =
                hrService.memberAttendanceDailyStatuses(user, ym.atDay(1), ym.atEndOfMonth());
        // 팀 필터 옵션은 필터 적용 전 전체 스코프 기준
        java.util.Map<Long, String> teams = new java.util.TreeMap<>();
        statuses.forEach(s -> {
            if (s.getTeamId() != null) {
                teams.putIfAbsent(s.getTeamId(), s.getTeamName());
            }
        });
        final Long teamFilter = teamId;
        final String kw = keyword;
        List<AttendanceDailyStatus> filtered = statuses.stream()
                .filter(s -> teamFilter == null || teamFilter.equals(s.getTeamId()))
                .filter(s -> kw.isEmpty() || containsIgnoreCase(s.getMemberName(), kw)
                        || containsIgnoreCase(s.getTeamName(), kw))
                .toList();
        int days = ym.lengthOfMonth();
        java.util.Map<Long, MatrixRow> rows = new java.util.LinkedHashMap<>();
        for (AttendanceDailyStatus s : filtered) {
            MatrixRow row = rows.computeIfAbsent(s.getUserId(), id -> new MatrixRow(
                    s.getUserId(), s.getMemberName(), s.getTeamName(),
                    new java.util.ArrayList<>(java.util.Collections.nCopies(days, (AttendanceDailyStatus) null))));
            row.cells().set(s.getWorkDate().getDayOfMonth() - 1, s);
        }
        List<MatrixDay> matrixDays = new java.util.ArrayList<>();
        for (int d = 1; d <= days; d++) {
            LocalDate date = ym.atDay(d);
            matrixDays.add(new MatrixDay(d, date.toString(), date.getDayOfWeek().getValue() >= 6));
        }
        addCommon(model, user, "근태 매트릭스", "membersMatrix");
        model.addAttribute("matrixMonth", ym.toString());
        model.addAttribute("matrixMonthLabel", ym.getYear() + "년 " + ym.getMonthValue() + "월");
        model.addAttribute("matrixPrev", ym.minusMonths(1).toString());
        model.addAttribute("matrixNext", ym.plusMonths(1).toString());
        model.addAttribute("matrixDays", matrixDays);
        model.addAttribute("matrixRows", rows.values().stream()
                .sorted(java.util.Comparator.comparing(MatrixRow::teamName, java.util.Comparator.nullsLast(String::compareTo))
                        .thenComparing(MatrixRow::memberName))
                .toList());
        model.addAttribute("matrixTeams", teams);
        model.addAttribute("selectedTeamId", teamId);
        model.addAttribute("query", keyword);
        // [A4/UX3] 월 마감 — 근태 확정 흐름상 매트릭스에서 관리(마감 상태 배지 + 관리 모달)
        if (hrService.canManageHr(user)) {
            model.addAttribute("lockedMonths", hrService.lockedMonths());
            model.addAttribute("lockEvents", hrService.monthLockEvents());
            model.addAttribute("matrixMonthLocked", hrService.isMonthLocked(ym.atDay(1)));
            model.addAttribute("defaultLockYm", java.time.YearMonth.now().minusMonths(1).toString());
            if (StringUtils.hasText(lockYm)) {
                try {
                    model.addAttribute("lockPreview",
                            hrService.monthLockPreview(user, java.time.YearMonth.parse(lockYm.trim())));
                } catch (java.time.format.DateTimeParseException ignored) {
                }
            }
        }
        return "hr/members-matrix";
    }

    public record MatrixDay(int day, String iso, boolean weekend) {}
    public record MatrixRow(Long userId, String memberName, String teamName, List<AttendanceDailyStatus> cells) {}

    /** [A7] 월별 근태 집계 CSV — 직원×일자 상태 + 상태별 합계 (급여 담당자 제공용). */
    @GetMapping("/hr/members/matrix/csv")
    public org.springframework.http.ResponseEntity<byte[]> matrixCsv(@AuthenticationPrincipal CustomUserDetails user,
                                                                     @RequestParam(required = false) String month,
                                                                     @RequestParam(required = false) Long teamId) {
        if (!hrService.canViewMemberHrOverview(user)) {
            throw new BusinessException("근태 집계 CSV는 조직 관리자만 내려받을 수 있습니다.");
        }
        java.time.YearMonth ym;
        try {
            ym = month == null ? java.time.YearMonth.now() : java.time.YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            ym = java.time.YearMonth.now();
        }
        int days = ym.lengthOfMonth();
        List<AttendanceDailyStatus> statuses =
                hrService.memberAttendanceDailyStatuses(user, ym.atDay(1), ym.atEndOfMonth()).stream()
                        .filter(s -> teamId == null || teamId.equals(s.getTeamId()))
                        .toList();
        java.util.Map<Long, MatrixRow> rows = new java.util.LinkedHashMap<>();
        for (AttendanceDailyStatus s : statuses) {
            MatrixRow row = rows.computeIfAbsent(s.getUserId(), id -> new MatrixRow(
                    s.getUserId(), s.getMemberName(), s.getTeamName(),
                    new java.util.ArrayList<>(java.util.Collections.nCopies(days, (AttendanceDailyStatus) null))));
            row.cells().set(s.getWorkDate().getDayOfMonth() - 1, s);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("팀,이름");
        for (int d = 1; d <= days; d++) {
            sb.append(',').append(d);
        }
        sb.append(",정상,지각,조퇴,결근,휴무·휴가,출장\n");
        java.util.List<MatrixRow> sorted = rows.values().stream()
                .sorted(java.util.Comparator.comparing(MatrixRow::teamName, java.util.Comparator.nullsLast(String::compareTo))
                        .thenComparing(MatrixRow::memberName))
                .toList();
        for (MatrixRow row : sorted) {
            sb.append(csv(row.teamName())).append(',').append(csv(row.memberName()));
            int normal = 0;
            int late = 0;
            int early = 0;
            int absent = 0;
            int holiday = 0;
            int trip = 0;
            for (AttendanceDailyStatus cell : row.cells()) {
                sb.append(',').append(cell == null ? "" : cell.getStatus().getShortLabel());
                if (cell == null) {
                    continue;
                }
                switch (cell.getStatus()) {
                    case NORMAL -> normal++;
                    case LATE -> late++;
                    case EARLY_LEAVE -> early++;
                    case ABSENT -> absent++;
                    case HOLIDAY -> holiday++;
                    case BUSINESS_TRIP -> trip++;
                    default -> { }
                }
            }
            sb.append(',').append(normal).append(',').append(late).append(',').append(early)
                    .append(',').append(absent).append(',').append(holiday).append(',').append(trip).append('\n');
        }
        return csvResponse(sb.toString(), ym + "_근태집계.csv");
    }

    /** [A7] 연차 사용 대장 CSV — 부여/조정/사용/잔여 (연도별). */
    @GetMapping("/hr/leave/ledger/csv")
    public org.springframework.http.ResponseEntity<byte[]> leaveLedgerCsv(@AuthenticationPrincipal CustomUserDetails user,
                                                                          @RequestParam(required = false) Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        StringBuilder sb = new StringBuilder();
        sb.append("팀,이름,등급,입사일,자동부여,조정,사용,잔여,연차수당,비고\n");
        for (com.portfolio.worksystem.service.HrService.LeaveLedgerRow row : hrService.leaveLedger(user, targetYear)) {
            sb.append(csv(row.teamName())).append(',')
                    .append(csv(row.memberName())).append(',')
                    .append(csv(row.gradeName())).append(',')
                    .append(row.hireDate() == null ? "" : row.hireDate()).append(',')
                    .append(row.entitlement().stripTrailingZeros().toPlainString()).append(',')
                    .append(row.adjusted().stripTrailingZeros().toPlainString()).append(',')
                    .append(row.used().stripTrailingZeros().toPlainString()).append(',')
                    .append(row.remaining().stripTrailingZeros().toPlainString()).append(',')
                    // 금액을 못 낸 사람은 빈 칸으로 둔다 — 0 으로 채우면 "안 줘도 되는 사람"과 섞인다.
                    .append(row.allowance() == null ? "" : row.allowance().toPlainString()).append(',')
                    .append(csv(row.note())).append('\n');
        }
        return csvResponse(sb.toString(), targetYear + "년_연차대장.csv");
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")
                ? "\"" + escaped + "\"" : escaped;
    }

    /** UTF-8 BOM 포함 CSV 응답 — 엑셀 한글 호환. */
    private org.springframework.http.ResponseEntity<byte[]> csvResponse(String content, String filename) {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(out);
    }

    /** [A3] HR 직권 정정 — 사유 필수, 감사 로그(V72). */
    @PostMapping("/hr/members/matrix/adjust")
    public String adjustAttendance(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam Long userId,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) java.time.LocalTime checkIn,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) java.time.LocalTime checkOut,
                                   @RequestParam com.portfolio.worksystem.model.AttendanceStatus status,
                                   @RequestParam String reason,
                                   @RequestParam(required = false) String month,
                                   RedirectAttributes ra) {
        runWithMessage(() -> hrService.adminAdjustAttendance(user, userId, workDate, checkIn, checkOut, status, reason),
                "근태를 직권 정정했습니다.", ra);
        return "redirect:/hr/members/matrix" + (StringUtils.hasText(month) ? "?month=" + month : "");
    }

    /** [A5] 연차 원장 — 직원별 부여/조정/사용/잔여 + 수동 조정. */
    @GetMapping("/hr/leave/ledger")
    public String leaveLedger(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam(required = false) Integer year,
                              @RequestParam(required = false, name = "q") String query,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "1") int apage,
                              Model model) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        List<com.portfolio.worksystem.service.HrService.LeaveLedgerRow> rows =
                hrService.leaveLedger(user, targetYear).stream()
                        .filter(r -> keyword.isEmpty() || containsIgnoreCase(r.memberName(), keyword)
                                || containsIgnoreCase(r.teamName(), keyword))
                        .toList();
        List<com.portfolio.worksystem.model.LeaveBalanceAdjustment> adjustments =
                hrService.leaveAdjustments(null, targetYear);
        // [UX13] 원장·조정 이력 페이징
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, rows.size());
        com.portfolio.worksystem.model.PageInfo adjPageInfo = new com.portfolio.worksystem.model.PageInfo(apage, 20, adjustments.size());
        String encQ = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        addCommon(model, user, "연차 관리", "leaveLedger");
        // [D3] 연차 부여 기준 정책
        model.addAttribute("accrualPolicy", hrService.leaveAccrualPolicy());
        model.addAttribute("currentYear", LocalDate.now().getYear());
        model.addAttribute("ledgerYear", targetYear);
        model.addAttribute("ledgerRows", com.portfolio.worksystem.model.PageInfo.slice(rows, pageInfo));
        model.addAttribute("ledgerTotal", rows.size());
        // [D4] 합계는 <검색 결과 전체> 기준이다. 화면은 20건씩 끊어 보여 주므로 현재 페이지만
        // 더하면 실제 지급액과 다른 숫자가 나온다.
        model.addAttribute("allowanceTotal", rows.stream()
                .map(com.portfolio.worksystem.service.HrService.LeaveLedgerRow::allowance)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        model.addAttribute("unusedTotal", rows.stream()
                .map(r -> r.remaining().max(java.math.BigDecimal.ZERO))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        // 금액을 못 낸 인원 — 등급 미지정이거나 그 해 단가가 미설정이라 합계에서 빠진 사람들.
        model.addAttribute("allowanceUnsettled", rows.stream()
                .filter(r -> r.allowance() == null && r.remaining().signum() > 0)
                .count());
        model.addAttribute("ledgerAdjustments", com.portfolio.worksystem.model.PageInfo.slice(adjustments, adjPageInfo));
        model.addAttribute("adjustmentTotal", adjustments.size());
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("adjPageInfo", adjPageInfo);
        model.addAttribute("pageBaseUrl", "/hr/leave/ledger?year=" + targetYear + "&q=" + encQ + "&apage=" + apage + "&page=");
        model.addAttribute("adjPageBaseUrl", "/hr/leave/ledger?year=" + targetYear + "&q=" + encQ + "&page=" + page + "&apage=");
        model.addAttribute("query", keyword);
        return "hr/leave-ledger";
    }

    /**
     * [D3] 연차 부여 기준 정책 저장 — scope: THIS_YEAR | FROM_YEAR | RETRO_ALL.
     *
     * <p>maxAnnualDays 는 빈 문자열로 올 수 있다(상한 없음). {@code Integer} 로 받으면
     * 빈 값에서 타입 변환이 터지므로 문자열로 받아 직접 푼다.
     */
    @PostMapping("/hr/leave/ledger/policy")
    public String updateLeavePolicy(@AuthenticationPrincipal CustomUserDetails user,
                                    @RequestParam String basis,
                                    @RequestParam String scope,
                                    @RequestParam(required = false) Integer fromYear,
                                    @RequestParam(required = false) String maxAnnualDays,
                                    RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message",
                    hrService.updateLeaveAccrualPolicy(user, basis, scope, fromYear, parseCap(maxAnnualDays)));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/hr/leave/ledger";
    }

    /** 빈 값 = 상한 없음(null). 숫자가 아니면 서비스의 범위 검증 메시지가 나오도록 그대로 넘긴다. */
    private Integer parseCap(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("부여 상한은 숫자로 입력하세요. 상한을 두지 않으려면 비워 두세요.");
        }
    }

    @PostMapping("/hr/leave/ledger/adjust")
    public String adjustLeaveBalance(@AuthenticationPrincipal CustomUserDetails user,
                                     @RequestParam Long userId,
                                     @RequestParam int year,
                                     @RequestParam java.math.BigDecimal deltaDays,
                                     @RequestParam String reason,
                                     RedirectAttributes ra) {
        runWithMessage(() -> hrService.adjustLeaveBalance(user, userId, year, deltaDays, reason),
                "연차를 조정했습니다.", ra);
        return "redirect:/hr/leave/ledger?year=" + year;
    }

    /** [A2] 내 근태 월 캘린더 뷰모델 — status null = 요일 휴무(주말 등, 현황 미생성일). */
    /** [UX42] 캘린더 오버레이 항목 — 휴가/출장 + 결재 진행 상태 배지. */
    public record CalOverlayItem(String kind, String label, String aprLabel, String aprCss, String aprDetail) {}

    public record AttCalDay(int day, String iso, boolean weekend, boolean today, AttendanceDailyStatus status,
                            List<CalOverlayItem> items) {}

    /** [UX42] 월 범위 내 내 휴가·출장(반려 제외)을 날짜별 결재상태 배지로. */
    private java.util.Map<String, List<CalOverlayItem>> approvalOverlay(Long userId, java.time.YearMonth month) {
        java.util.Map<String, List<CalOverlayItem>> map = new java.util.HashMap<>();
        LocalDate mStart = month.atDay(1);
        LocalDate mEnd = month.atEndOfMonth();
        for (LeaveRequest r : hrService.ownLeaveRequests(userId, month.getYear())) {
            if (r.getStatus() == null || "REJECTED".equals(r.getStatus().name())) continue;
            if (r.getStartDate() == null || r.getEndDate() == null) continue;
            if (r.getEndDate().isBefore(mStart) || r.getStartDate().isAfter(mEnd)) continue;
            var p = approvalService.approvalProgress(ApprovalDocumentType.LEAVE, r.getId(), r.getStatus().name());
            addOverlay(map, mStart, mEnd, r.getStartDate(), r.getEndDate(),
                    new CalOverlayItem("휴가", r.getTypeLabel(), p.label(), p.css(), p.detail()));
        }
        for (BusinessTrip t : hrService.ownBusinessTrips(userId, month.getYear())) {
            if (t.getStatus() == null || "REJECTED".equals(t.getStatus().name())) continue;
            if (t.getStartDate() == null || t.getEndDate() == null) continue;
            if (t.getEndDate().isBefore(mStart) || t.getStartDate().isAfter(mEnd)) continue;
            var p = approvalService.approvalProgress(ApprovalDocumentType.TRIP, t.getId(), t.getStatus().name());
            addOverlay(map, mStart, mEnd, t.getStartDate(), t.getEndDate(),
                    new CalOverlayItem("출장", t.getDestination(), p.label(), p.css(), p.detail()));
        }
        return map;
    }

    private void addOverlay(java.util.Map<String, List<CalOverlayItem>> map, LocalDate mStart, LocalDate mEnd,
                            LocalDate start, LocalDate end, CalOverlayItem item) {
        LocalDate from = start.isBefore(mStart) ? mStart : start;
        LocalDate to = end.isAfter(mEnd) ? mEnd : end;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            map.computeIfAbsent(d.toString(), k -> new java.util.ArrayList<>()).add(item);
        }
    }

    private List<List<AttCalDay>> buildAttendanceCalendar(CustomUserDetails user, java.time.YearMonth month) {
        LocalDate first = month.atDay(1);
        java.util.Map<LocalDate, AttendanceDailyStatus> byDate = new java.util.HashMap<>();
        hrService.attendanceDailyStatuses(user, first, month.atEndOfMonth()).stream()
                .filter(s -> user.getUserId().equals(s.getUserId()))
                .forEach(s -> byDate.putIfAbsent(s.getWorkDate(), s));
        java.util.Map<String, List<CalOverlayItem>> overlay = approvalOverlay(user.getUserId(), month);
        LocalDate today = LocalDate.now();
        List<List<AttCalDay>> weeks = new java.util.ArrayList<>();
        List<AttCalDay> week = new java.util.ArrayList<>();
        int offset = first.getDayOfWeek().getValue() % 7; // 일요일 시작(일=0)
        for (int i = 0; i < offset; i++) {
            week.add(new AttCalDay(0, null, false, false, null, List.of()));
        }
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            week.add(new AttCalDay(d, date.toString(), date.getDayOfWeek().getValue() >= 6,
                    date.equals(today), byDate.get(date),
                    overlay.getOrDefault(date.toString(), List.of())));
            if (week.size() == 7) {
                weeks.add(week);
                week = new java.util.ArrayList<>();
            }
        }
        if (!week.isEmpty()) {
            while (week.size() < 7) {
                week.add(new AttCalDay(0, null, false, false, null, List.of()));
            }
            weeks.add(week);
        }
        return weeks;
    }

    /** [A6] 법정공휴일 일괄 생성 — 내장 데이터(대체공휴일 포함), 중복 일자 건너뜀. */
    @PostMapping("/hr/schedules/holiday/generate")
    public String generateHolidays(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam int year,
                                   RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message", hrService.generatePublicHolidays(user, year));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/hr/schedules?calYear=" + year;
    }

    /** [A6] 임시공휴일 등 CSV 반입 — yyyy-MM-dd,이름[,PUBLIC|COMPANY] */
    /** [UX48] 공휴일 업로드용 CSV 양식 다운로드 — 반입 파서 형식과 1:1(BOM 포함, 엑셀 한글 안전). */
    @GetMapping("/hr/schedules/holiday/template.csv")
    public ResponseEntity<byte[]> holidayCsvTemplate() {
        String csv = "날짜,이름,구분\n"
                + "# 형식: YYYY-MM-DD,휴무일 이름,구분(PUBLIC=법정·임시공휴일 / COMPANY=회사휴무). 구분을 비우면 PUBLIC.\n"
                + "# '#'로 시작하는 줄과 첫 줄(제목)은 무시됩니다. 아래 예시를 지우고 실제 데이터를 입력하세요.\n"
                + "2026-03-04,대통령선거일,PUBLIC\n"
                + "2026-12-24,창립기념일,COMPANY\n";
        byte[] body = ("﻿" + csv).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("공휴일_업로드_양식.csv",
                                java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    @PostMapping("/hr/schedules/holiday/import")
    public String importHolidays(@AuthenticationPrincipal CustomUserDetails user,
                                 @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                 RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message", hrService.importHolidaysCsv(user, file));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/hr/schedules";
    }

    /** [B3] 연간 휴무 캘린더 뷰모델 — 일요일 시작 주. day 0 = 빈 칸. */
    /** [UX26] scheduleTitles = 그 날짜에 걸친 회사 일정 제목(줄바꿈 구분, 호버 툴팁·dot 표시용) */
    public record HolidayCalDay(int day, String iso, String category, String name, boolean weekend, String scheduleTitles) {}
    public record HolidayCalMonth(int month, List<List<HolidayCalDay>> weeks) {}

    private List<HolidayCalMonth> buildHolidayCalendar(int year) {
        java.util.Map<LocalDate, com.portfolio.worksystem.model.CompanyHoliday> holidayMap = new java.util.HashMap<>();
        hrService.companyHolidays(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                .forEach(h -> holidayMap.putIfAbsent(h.getHolidayDate(), h));
        // [UX26] 일정도 캘린더에 표시 — 날짜별 제목 수집(기간 일정은 각 일자에 매핑)
        java.util.Map<LocalDate, java.util.List<String>> scheduleMap = new java.util.HashMap<>();
        for (com.portfolio.worksystem.model.CompanySchedule sc :
                hrService.companySchedules(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))) {
            if (sc.getStartAt() == null) continue;
            LocalDate d = sc.getStartAt().toLocalDate();
            LocalDate e = sc.getEndAt() == null ? d : sc.getEndAt().toLocalDate();
            for (; !d.isAfter(e); d = d.plusDays(1)) {
                if (d.getYear() == year) {
                    scheduleMap.computeIfAbsent(d, k -> new java.util.ArrayList<>()).add(sc.getTitle());
                }
            }
        }
        List<HolidayCalMonth> months = new java.util.ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            LocalDate first = LocalDate.of(year, m, 1);
            List<List<HolidayCalDay>> weeks = new java.util.ArrayList<>();
            List<HolidayCalDay> week = new java.util.ArrayList<>();
            int offset = first.getDayOfWeek().getValue() % 7; // 일요일 시작(일=0)
            for (int i = 0; i < offset; i++) {
                week.add(new HolidayCalDay(0, null, null, null, false, null));
            }
            for (int d = 1; d <= first.lengthOfMonth(); d++) {
                LocalDate date = LocalDate.of(year, m, d);
                com.portfolio.worksystem.model.CompanyHoliday holiday = holidayMap.get(date);
                int dow = date.getDayOfWeek().getValue();
                java.util.List<String> titles = scheduleMap.get(date);
                week.add(new HolidayCalDay(d, date.toString(),
                        holiday == null ? null : (holiday.isPublicHoliday() ? "PUBLIC" : "COMPANY"),
                        holiday == null ? null : holiday.getName(), dow >= 6,
                        titles == null ? null : String.join("\n", titles)));
                if (week.size() == 7) {
                    weeks.add(week);
                    week = new java.util.ArrayList<>();
                }
            }
            if (!week.isEmpty()) {
                while (week.size() < 7) {
                    week.add(new HolidayCalDay(0, null, null, null, false, null));
                }
                weeks.add(week);
            }
            months.add(new HolidayCalMonth(m, weeks));
        }
        return months;
    }

    @PostMapping("/hr/schedules")
    public String createSchedule(@AuthenticationPrincipal CustomUserDetails user,
                                 @ModelAttribute CompanyScheduleForm form,
                                 RedirectAttributes ra) {
        runWithMessage(() -> hrService.createCompanySchedule(user, form), "일정을 등록했습니다.", ra);
        return "redirect:/hr/schedules";
    }

    @PostMapping("/hr/schedules/{id}/update")
    public String updateSchedule(@AuthenticationPrincipal CustomUserDetails user,
                                 @PathVariable Long id,
                                 @ModelAttribute CompanyScheduleForm form,
                                 RedirectAttributes ra) {
        runWithMessage(() -> hrService.updateCompanySchedule(user, id, form), "일정을 수정했습니다.", ra);
        return "redirect:/hr/schedules";
    }

    @PostMapping("/hr/schedules/{id}/delete")
    public String deleteSchedule(@AuthenticationPrincipal CustomUserDetails user,
                                 @PathVariable Long id,
                                 RedirectAttributes ra) {
        runWithMessage(() -> hrService.deleteCompanySchedule(user, id), "일정을 삭제했습니다.", ra);
        return "redirect:/hr/schedules";
    }

    @PostMapping("/hr/schedules/holiday")
    public String saveScheduleHoliday(@AuthenticationPrincipal CustomUserDetails user,
                                      @ModelAttribute HolidayForm form,
                                      RedirectAttributes ra) {
        runWithMessage(() -> hrService.saveCompanyHoliday(user, form), "회사 휴무일을 저장했습니다.", ra);
        return "redirect:/hr/schedules";
    }

    @PostMapping("/hr/schedules/holiday/delete")
    public String deleteScheduleHoliday(@AuthenticationPrincipal CustomUserDetails user,
                                        @RequestParam LocalDate holidayDate,
                                        RedirectAttributes ra) {
        runWithMessage(() -> hrService.deleteCompanyHoliday(user, holidayDate), "회사 휴무일을 삭제했습니다.", ra);
        return "redirect:/hr/schedules";
    }

    @GetMapping("/hr/delegations")
    public String delegations(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam(required = false, name = "q") String query,
                              @RequestParam(defaultValue = "all") String status,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                              @RequestParam(required = false) String cal,
                              Model model) {
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        String statusFilter = StringUtils.hasText(status) ? status.trim() : "all";
        List<com.portfolio.worksystem.model.ApprovalDelegation> all = approvalService.delegations(user).stream()
                .filter(d -> "all".equals(statusFilter)
                        || ("active".equals(statusFilter) && Boolean.TRUE.equals(d.getActive()))
                        || ("inactive".equals(statusFilter) && !Boolean.TRUE.equals(d.getActive())))
                .filter(d -> keyword.isEmpty()
                        || containsIgnoreCase(d.getDelegateName(), keyword)
                        || containsIgnoreCase(d.getApproverName(), keyword)
                        || containsIgnoreCase(d.getReason(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, all.size());
        addCommon(model, user, "대리승인자", "delegations");
        model.addAttribute("delegations", com.portfolio.worksystem.model.PageInfo.slice(all, pageInfo));
        model.addAttribute("users", organizationService.findUsers(user));
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/hr/delegations?q="
                + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                + "&status=" + statusFilter + "&page=");
        model.addAttribute("query", keyword);
        model.addAttribute("statusFilter", statusFilter);
        // [UX41] 어느 날짜를 지정할지 참고 — 내 휴가·출장(결재상태 포함) 캘린더 + 신청 유도 시 기간 프리필
        java.time.YearMonth delCalMonth;
        try {
            delCalMonth = cal != null ? java.time.YearMonth.parse(cal)
                    : (start != null ? java.time.YearMonth.from(start) : java.time.YearMonth.now());
        } catch (java.time.format.DateTimeParseException e) {
            delCalMonth = java.time.YearMonth.now();
        }
        model.addAttribute("delCalendar", buildAttendanceCalendar(user, delCalMonth));
        model.addAttribute("delCalMonthLabel", delCalMonth.getYear() + "년 " + delCalMonth.getMonthValue() + "월");
        model.addAttribute("delCalPrev", delCalMonth.minusMonths(1).toString());
        model.addAttribute("delCalNext", delCalMonth.plusMonths(1).toString());
        model.addAttribute("prefillStart", start);
        model.addAttribute("prefillEnd", end == null ? start : end);
        return "hr/delegations";
    }

    @PostMapping("/hr/delegations")
    public String createDelegation(@AuthenticationPrincipal CustomUserDetails user,
                                   @Valid @ModelAttribute ApprovalDelegationForm form,
                                   BindingResult bindingResult,
                                   RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", "대리승인자와 기간을 입력하세요.");
            return "redirect:/hr/delegations";
        }
        runWithMessage(() -> approvalService.createDelegation(user, form), "대리승인자를 지정했습니다.", ra);
        return "redirect:/hr/delegations";
    }

    @PostMapping("/hr/delegations/{id}/delete")
    public String cancelDelegation(@AuthenticationPrincipal CustomUserDetails user,
                                   @PathVariable Long id,
                                   RedirectAttributes ra) {
        runWithMessage(() -> approvalService.cancelDelegation(user, id), "대리승인자 지정을 해제했습니다.", ra);
        return "redirect:/hr/delegations";
    }

    @GetMapping("/hr/attendance")
    public String attendance(@AuthenticationPrincipal CustomUserDetails user,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @RequestParam(required = false) LocalDate from,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @RequestParam(required = false) LocalDate to,
                             @RequestParam(required = false, name = "q") String query,
                             @RequestParam(required = false, name = "cq") String correctionQuery,
                             @RequestParam(required = false, name = "mq") String memberQuery,
                             @RequestParam(defaultValue = "all") String contractor,
                             @RequestParam(required = false) String cal,
                             @RequestParam(required = false) String tab,
                             @RequestParam(required = false) String lq,
                             @RequestParam(required = false) String lstatus,
                             @RequestParam(required = false) String fq,
                             @RequestParam(required = false) String fstatus,
                             @RequestParam(required = false) String tq,
                             @RequestParam(required = false) String tstatus,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "1") int cpage,
                             @RequestParam(defaultValue = "1") int mpage,
                             @RequestParam(defaultValue = "1") int lpage,
                             @RequestParam(defaultValue = "1") int fpage,
                             @RequestParam(defaultValue = "1") int tpage,
                             @org.springframework.web.bind.annotation.RequestHeader(name = "X-Worksystem-Fragment", required = false) String fragment,
                             jakarta.servlet.http.HttpServletRequest request,
                             Model model) {
        String currentWeekKey = WeekUtils.getCurrentWeekKey();
        LocalDate fromDate = from == null ? WeekUtils.getWeekStart(currentWeekKey) : from;
        LocalDate toDate = to == null ? WeekUtils.getWeekEnd(currentWeekKey) : to;
        if (fromDate.isAfter(toDate)) {
            LocalDate originalFrom = fromDate;
            fromDate = toDate;
            toDate = originalFrom;
            model.addAttribute("error", "시작일이 종료일보다 늦어 조회 기간을 자동으로 맞바꿔 표시했습니다.");
        }
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        String correctionKeyword = StringUtils.hasText(correctionQuery) ? correctionQuery.trim() : "";
        String memberKeyword = StringUtils.hasText(memberQuery) ? memberQuery.trim() : "";
        String contractorFilter = normalizeContractorFilter(contractor);
        // [O-5] 본인 근태 기록만
        List<AttendanceDailyStatus> dailyStatuses = filterAttendanceStatuses(
                hrService.attendanceDailyStatuses(user, fromDate, toDate).stream()
                        .filter(record -> user.getUserId().equals(record.getUserId()))
                        .toList(),
                keyword
        ).stream().filter(record -> matchesContractor(record.getContractor(), contractorFilter)).toList();
        List<CompanyHoliday> holidays = hrService.companyHolidays(fromDate, toDate);
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 12, dailyStatuses.size());
        String encodedKeyword = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        String encodedCorrectionKeyword = java.net.URLEncoder.encode(correctionKeyword, java.nio.charset.StandardCharsets.UTF_8);
        String encodedMemberKeyword = java.net.URLEncoder.encode(memberKeyword, java.nio.charset.StandardCharsets.UTF_8);
        String attendanceBase = "/hr/attendance?from=" + fromDate + "&to=" + toDate + "&q=" + encodedKeyword
                + "&contractor=" + contractorFilter;
        String pageBaseUrl = attendanceBase + "&cq=" + encodedCorrectionKeyword + "&mq=" + encodedMemberKeyword
                + "&cpage=" + cpage + "&mpage=" + mpage + "&page=";
        addCommon(model, user, "내 근태", "attendance");
        model.addAttribute("records", com.portfolio.worksystem.model.PageInfo.slice(dailyStatuses, pageInfo));
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", pageBaseUrl);
        model.addAttribute("todayRecord", hrService.todayAttendance(user));
        model.addAttribute("attendanceSetting", hrService.attendanceSetting());
        model.addAttribute("todaySchedule", hrService.workSchedule(user.getUserId(), LocalDate.now()));
        // [A1] 웹 출퇴근 체크 — 공휴일·휴무요일·출장·전일휴가면 버튼 비활성 + 사유 안내
        model.addAttribute("todayCheckBlockReason", hrService.todayCheckBlockReason(user.getUserId()));
        // [UX19] 진입 시 미체크 알림(출근 안 찍음 / 기준 퇴근 지났는데 미퇴근)
        model.addAttribute("attendanceReminder", hrService.attendanceReminder(user.getUserId()));
        // [A2] 내 근태 월 캘린더
        java.time.YearMonth calMonth;
        try {
            calMonth = cal == null ? java.time.YearMonth.now() : java.time.YearMonth.parse(cal);
        } catch (java.time.format.DateTimeParseException e) {
            calMonth = java.time.YearMonth.now();
        }
        model.addAttribute("calMonthLabel", calMonth.getYear() + "년 " + calMonth.getMonthValue() + "월");
        model.addAttribute("calPrev", calMonth.minusMonths(1).toString());
        model.addAttribute("calNext", calMonth.plusMonths(1).toString());
        model.addAttribute("cal", calMonth.toString());
        model.addAttribute("attCalendar", buildAttendanceCalendar(user, calMonth));
        model.addAttribute("overrides", hrService.attendanceOverrides(fromDate, toDate));
        model.addAttribute("holidays", holidays);
        model.addAttribute("fromDate", fromDate);
        model.addAttribute("toDate", toDate);
        model.addAttribute("query", keyword);
        model.addAttribute("correctionQuery", correctionKeyword);
        model.addAttribute("memberQuery", memberKeyword);
        model.addAttribute("contractorFilter", contractorFilter);
        model.addAttribute("attendanceCount", dailyStatuses.stream().filter(record -> record.getCheckInAt() != null).count());
        model.addAttribute("lateCount", dailyStatuses.stream().filter(record -> record.getStatus() == AttendanceStatus.LATE).count());
        model.addAttribute("absentCount", dailyStatuses.stream().filter(record -> record.getStatus() == AttendanceStatus.ABSENT).count());
        model.addAttribute("tripDayCount", dailyStatuses.stream().filter(record -> record.getStatus() == AttendanceStatus.BUSINESS_TRIP).count());
        model.addAttribute("holidayCount", holidays.size());
        model.addAttribute("canManageHr", hrService.canManageHr(user));
        // [O-5] 본인 근태 정정 신청만
        List<AttendanceCorrection> corrections = filterAttendanceCorrections(
                hrService.ownAttendanceCorrections(user.getUserId(), hrService.currentYear()),
                correctionKeyword).stream()
                .filter(correction -> matchesContractor(correction.getContractor(), contractorFilter))
                .toList();
        com.portfolio.worksystem.model.PageInfo correctionPageInfo =
                new com.portfolio.worksystem.model.PageInfo(cpage, 12, corrections.size());
        List<AttendanceCorrection> pagedCorrections =
                com.portfolio.worksystem.model.PageInfo.slice(corrections, correctionPageInfo);
        model.addAttribute("attendanceCorrections", pagedCorrections);
        model.addAttribute("attendanceCorrectionSteps", correctionStepsByRequest(pagedCorrections));
        // [O-5/O-8] 팀원/직원 영역은 /hr/members에서 — 여기서는 본인만
        model.addAttribute("correctionPageInfo", correctionPageInfo);
        model.addAttribute("correctionPageBaseUrl", attendanceBase + "&cq=" + encodedCorrectionKeyword
                + "&mq=" + encodedMemberKeyword + "&page=" + page + "&mpage=" + mpage + "&cpage=");
        // [UX17] 휴가·근태·시차·출장 통합 — 같은 화면에 목록 탭으로 병합
        int hrYear = fromDate.getYear();
        fillLeaveTab(model, user, hrYear, lq, lstatus, lpage);
        fillFlexTab(model, user, hrYear, fq, fstatus, fpage);
        fillTripTab(model, user, hrYear, tq, tstatus, tpage);
        model.addAttribute("hrTab", tab != null && java.util.Set.of("attendance", "leave", "flex", "trip", "correction").contains(tab) ? tab : "attendance");
        model.addAttribute("attendanceHistoryUrl", request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
        model.addAttribute("hrYear", hrYear);
        model.addAttribute("leaveTypes", hrService.activeLeaveTypeDefs());
        model.addAttribute("shiftGroups", hrService.activeShiftGroups());
        return "attendance-history".equals(fragment) ? "hr/attendance :: attendanceHistory" : "hr/attendance";
    }

    /* ── [UX17] 통합 화면 탭 모델 채우기(휴가/시차/출장) — 파라미터 접두사로 충돌 회피 ── */

    private void fillLeaveTab(Model model, CustomUserDetails user, int year, String q, String status, int page) {
        String keyword = StringUtils.hasText(q) ? q.trim() : "";
        String statusFilter = StringUtils.hasText(status) && !"ALL".equals(status) ? status.trim() : "";
        List<LeaveRequest> requests = hrService.ownLeaveRequests(user.getUserId(), year).stream()
                .filter(r -> statusFilter.isEmpty() || (r.getStatus() != null && statusFilter.equals(r.getStatus().name())))
                .filter(r -> keyword.isEmpty() || containsIgnoreCase(r.getReason(), keyword)
                        || (r.getType() != null && containsIgnoreCase(r.getTypeLabel(), keyword)))
                .toList();
        com.portfolio.worksystem.model.PageInfo pi = new com.portfolio.worksystem.model.PageInfo(page, 20, requests.size());
        List<LeaveRequest> paged = com.portfolio.worksystem.model.PageInfo.slice(requests, pi);
        model.addAttribute("balance", hrService.leaveBalance(user, year));
        model.addAttribute("myLeaveAdjustments", hrService.leaveAdjustments(user.getUserId(), year));
        model.addAttribute("leaveEntitlementSummary", hrService.leaveEntitlementSummary(user, year));
        model.addAttribute("leaveRequests", paged);
        model.addAttribute("leaveApprovalSteps", leaveStepsByRequest(paged));
        model.addAttribute("leaveTotal", requests.size());
        model.addAttribute("pendingLeaveCount", requests.stream().filter(LeaveRequest::isPending).count());
        model.addAttribute("leavePageInfo", pi);
        model.addAttribute("leavePageBaseUrl", "/hr/attendance?tab=leave&lq="
                + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                + "&lstatus=" + statusFilter + "&lpage=");
        model.addAttribute("leaveQuery", keyword);
        model.addAttribute("leaveStatusFilter", statusFilter);
        model.addAttribute("leaveStatuses", com.portfolio.worksystem.model.LeaveStatus.values());
    }

    private void fillFlexTab(Model model, CustomUserDetails user, int year, String q, String status, int page) {
        String keyword = StringUtils.hasText(q) ? q.trim() : "";
        String statusFilter = StringUtils.hasText(status) && !"ALL".equals(status) ? status.trim() : "";
        List<FlexTimeRequest> requests = hrService.ownFlexTimeRequests(user.getUserId(), year).stream()
                .filter(r -> statusFilter.isEmpty() || (r.getStatus() != null && statusFilter.equals(r.getStatus().name())))
                .filter(r -> keyword.isEmpty() || containsIgnoreCase(r.getReason(), keyword)
                        || containsIgnoreCase(r.getShiftGroupDisplay(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pi = new com.portfolio.worksystem.model.PageInfo(page, 20, requests.size());
        List<FlexTimeRequest> paged = com.portfolio.worksystem.model.PageInfo.slice(requests, pi);
        model.addAttribute("flexTimeRequests", paged);
        model.addAttribute("flexTimeApprovalSteps", flexStepsByRequest(paged));
        model.addAttribute("flexTotal", requests.size());
        model.addAttribute("pendingFlexCount", requests.stream().filter(FlexTimeRequest::isPending).count());
        model.addAttribute("flexPageInfo", pi);
        model.addAttribute("flexPageBaseUrl", "/hr/attendance?tab=flex&fq="
                + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                + "&fstatus=" + statusFilter + "&fpage=");
        model.addAttribute("flexQuery", keyword);
        model.addAttribute("flexStatusFilter", statusFilter);
        model.addAttribute("flexTimeStatuses", FlexTimeStatus.values());
    }

    private void fillTripTab(Model model, CustomUserDetails user, int year, String q, String status, int page) {
        String keyword = StringUtils.hasText(q) ? q.trim() : "";
        String statusFilter = StringUtils.hasText(status) && !"ALL".equals(status) ? status.trim() : "";
        List<BusinessTrip> trips = hrService.ownBusinessTrips(user.getUserId(), year).stream()
                .filter(t -> statusFilter.isEmpty() || (t.getStatus() != null && statusFilter.equals(t.getStatus().name())))
                .filter(t -> keyword.isEmpty() || containsIgnoreCase(t.getDestination(), keyword)
                        || containsIgnoreCase(t.getPurpose(), keyword) || containsIgnoreCase(t.getCostNote(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pi = new com.portfolio.worksystem.model.PageInfo(page, 20, trips.size());
        List<BusinessTrip> paged = com.portfolio.worksystem.model.PageInfo.slice(trips, pi);
        model.addAttribute("trips", paged);
        // [UX47] 출장신청서·출장보고서 세트 — 행별 보고서 상태(미작성/작성 진입/문서 열람)
        model.addAttribute("tripReports",
                hrService.tripReportsByTripIds(paged.stream().map(BusinessTrip::getId).toList()));
        model.addAttribute("tripApprovalSteps", tripStepsByRequest(paged));
        model.addAttribute("tripTotal", trips.size());
        model.addAttribute("pendingTripCount", trips.stream().filter(BusinessTrip::isPending).count());
        model.addAttribute("tripPageInfo", pi);
        model.addAttribute("tripPageBaseUrl", "/hr/attendance?tab=trip&tq="
                + java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8)
                + "&tstatus=" + statusFilter + "&tpage=");
        model.addAttribute("tripQuery", keyword);
        model.addAttribute("tripStatusFilter", statusFilter);
        model.addAttribute("tripStatuses", BusinessTripStatus.values());
    }

    @PostMapping("/hr/attendance/check-in")
    public String checkIn(@AuthenticationPrincipal CustomUserDetails user, RedirectAttributes ra) {
        runWithMessage(() -> hrService.checkIn(user), "출근 처리되었습니다.", ra);
        return "redirect:/hr/attendance";
    }

    @PostMapping("/hr/attendance/check-out")
    public String checkOut(@AuthenticationPrincipal CustomUserDetails user, RedirectAttributes ra) {
        runWithMessage(() -> hrService.checkOut(user), "퇴근 처리되었습니다.", ra);
        return "redirect:/hr/attendance";
    }

    /** [UX20] 지금 출/퇴근을 찍으면 어떤 판정이 되는지 미리보기 — 확인 모달에 표시. */
    @GetMapping("/hr/attendance/check-preview")
    @ResponseBody
    public com.portfolio.worksystem.service.HrService.CheckPreview checkPreview(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam String kind) {
        return hrService.checkPreview(user.getUserId(), kind);
    }

    /** [B4/C1/C3/UX3] 근태 설정 대시보드 — 각 영역 readonly 요약 카드 + ⚙ 설정 모달. */
    @GetMapping("/hr/attendance/policy")
    public String attendancePolicy(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        if (!hrService.canManageHr(user)) {
            throw new BusinessException("근태 설정은 HR 담당자만 사용할 수 있습니다.");
        }
        addCommon(model, user, "근태 설정", "attendancePolicy");
        model.addAttribute("attendanceSetting", hrService.attendanceSetting());
        model.addAttribute("weekDays", hrService.weekSchedule().values().stream()
                .sorted(java.util.Comparator.comparing(com.portfolio.worksystem.model.AttendanceWeekDay::getDayOfWeek))
                .toList());
        LocalDate today = LocalDate.now();
        model.addAttribute("overrides", hrService.attendanceOverrides(today.minusMonths(1), today.plusMonths(6)));
        model.addAttribute("shiftGroups", hrService.shiftGroups());
        model.addAttribute("leaveTypeDefs", hrService.leaveTypeDefs());
        return "hr/attendance-policy";
    }

    /** [A4/UX3] 월 마감 — 타이핑 확인('마감합니다') 필수. 근태 매트릭스 화면에서 관리. */
    @PostMapping("/hr/attendance/month-lock")
    public String lockMonth(@AuthenticationPrincipal CustomUserDetails user,
                            @RequestParam String ym,
                            @RequestParam(required = false) String confirmText,
                            RedirectAttributes ra) {
        if (!"마감합니다".equals(confirmText == null ? "" : confirmText.trim())) {
            ra.addFlashAttribute("error", "확인 문구('마감합니다')를 정확히 입력해야 마감할 수 있습니다.");
            return "redirect:/hr/members/matrix?lockYm=" + ym;
        }
        runWithMessage(() -> hrService.lockMonth(user, java.time.YearMonth.parse(ym)),
                ym + "월 근태를 마감했습니다.", ra);
        return "redirect:/hr/members/matrix";
    }

    /** [UX3] 시차출근 조 일괄 저장 — 설정 모달에서 한 번에. */
    @PostMapping("/hr/shift-groups/save-all")
    public String saveAllShiftGroups(@AuthenticationPrincipal CustomUserDetails user,
                                     @ModelAttribute ShiftGroupBulkForm form,
                                     RedirectAttributes ra) {
        runWithMessage(() -> {
            for (com.portfolio.worksystem.model.ShiftGroupDef g : form.getGroups()) {
                if (g.getId() != null) {
                    hrService.updateShiftGroup(user, g);
                } else if (g.getCode() != null && !g.getCode().isBlank()) {
                    // [UX11] '+ 행 추가'로 들어온 신규 조
                    hrService.createShiftGroup(user, g);
                }
            }
        }, "시차출근 조를 저장했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    public static class ShiftGroupBulkForm {
        private java.util.List<com.portfolio.worksystem.model.ShiftGroupDef> groups = new java.util.ArrayList<>();

        public java.util.List<com.portfolio.worksystem.model.ShiftGroupDef> getGroups() {
            return groups;
        }

        public void setGroups(java.util.List<com.portfolio.worksystem.model.ShiftGroupDef> groups) {
            this.groups = groups;
        }
    }

    /** [UX3] 휴가 종류 일괄 저장 — 설정 모달에서 한 번에. */
    @PostMapping("/hr/leave-types/save-all")
    public String saveAllLeaveTypes(@AuthenticationPrincipal CustomUserDetails user,
                                    @ModelAttribute LeaveTypeBulkForm form,
                                    RedirectAttributes ra) {
        runWithMessage(() -> {
            for (com.portfolio.worksystem.model.LeaveTypeDef def : form.getTypes()) {
                if (def.getCode() == null || def.getCode().isBlank()) {
                    continue;
                }
                if (Boolean.TRUE.equals(def.getIsNew())) {
                    hrService.createLeaveTypeDef(user, def); // [UX11] '+ 행 추가' 신규 종류
                } else {
                    hrService.updateLeaveTypeDef(user, def);
                }
            }
        }, "휴가 종류를 저장했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    public static class LeaveTypeBulkForm {
        private java.util.List<com.portfolio.worksystem.model.LeaveTypeDef> types = new java.util.ArrayList<>();

        public java.util.List<com.portfolio.worksystem.model.LeaveTypeDef> getTypes() {
            return types;
        }

        public void setTypes(java.util.List<com.portfolio.worksystem.model.LeaveTypeDef> types) {
            this.types = types;
        }
    }

    /** [C2] 휴가 종류 정의 수정 — 근태 설정 화면 행별 저장. */
    @PostMapping("/hr/leave-types/{code}")
    public String updateLeaveType(@AuthenticationPrincipal CustomUserDetails user,
                                  @PathVariable String code,
                                  @ModelAttribute com.portfolio.worksystem.model.LeaveTypeDef form,
                                  RedirectAttributes ra) {
        form.setCode(code);
        runWithMessage(() -> hrService.updateLeaveTypeDef(user, form), "휴가 종류를 저장했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    @PostMapping("/hr/attendance/month-unlock")
    public String unlockMonth(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam String ym,
                              @RequestParam(required = false) String confirmText,
                              RedirectAttributes ra) {
        if (!"해제합니다".equals(confirmText == null ? "" : confirmText.trim())) {
            ra.addFlashAttribute("error", "확인 문구('해제합니다')를 정확히 입력해야 해제할 수 있습니다.");
            return "redirect:/hr/attendance/policy";
        }
        runWithMessage(() -> hrService.unlockMonth(user, java.time.YearMonth.parse(ym)),
                ym + "월 근태 마감을 해제했습니다.", ra);
        return "redirect:/hr/members/matrix";
    }

    @PostMapping("/hr/attendance/week-schedule")
    public String saveWeekSchedule(@AuthenticationPrincipal CustomUserDetails user,
                                   @ModelAttribute WeekScheduleForm form,
                                   RedirectAttributes ra) {
        runWithMessage(() -> hrService.saveWeekSchedule(user, form.getDays()), "요일별 근무시간표를 저장했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    public static class WeekScheduleForm {
        private java.util.List<com.portfolio.worksystem.model.AttendanceWeekDay> days = new java.util.ArrayList<>();

        public java.util.List<com.portfolio.worksystem.model.AttendanceWeekDay> getDays() {
            return days;
        }

        public void setDays(java.util.List<com.portfolio.worksystem.model.AttendanceWeekDay> days) {
            this.days = days;
        }
    }

    @PostMapping("/hr/shift-groups")
    public String createShiftGroup(@AuthenticationPrincipal CustomUserDetails user,
                                   @ModelAttribute com.portfolio.worksystem.model.ShiftGroupDef form,
                                   RedirectAttributes ra) {
        runWithMessage(() -> hrService.createShiftGroup(user, form), "시차출근 조를 추가했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    @PostMapping("/hr/shift-groups/{id}")
    public String updateShiftGroup(@AuthenticationPrincipal CustomUserDetails user,
                                   @PathVariable Long id,
                                   @ModelAttribute com.portfolio.worksystem.model.ShiftGroupDef form,
                                   RedirectAttributes ra) {
        form.setId(id);
        runWithMessage(() -> hrService.updateShiftGroup(user, form), "시차출근 조를 수정했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    @PostMapping("/hr/attendance/settings")
    public String updateAttendanceSetting(@AuthenticationPrincipal CustomUserDetails user,
                                           @ModelAttribute AttendanceSettingForm form,
                                           RedirectAttributes ra) {
        runWithMessage(() -> hrService.updateAttendanceSetting(user, form), "기본 근무시간을 저장했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    @PostMapping("/hr/attendance/override")
    public String saveAttendanceOverride(@AuthenticationPrincipal CustomUserDetails user,
                                          @ModelAttribute AttendanceOverrideForm form,
                                          RedirectAttributes ra) {
        runWithMessage(() -> hrService.saveAttendanceOverride(user, form), "일자별 근무시간 예외를 저장했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    @PostMapping("/hr/attendance/override/delete")
    public String deleteAttendanceOverride(@AuthenticationPrincipal CustomUserDetails user,
                                            @RequestParam LocalDate workDate,
                                            RedirectAttributes ra) {
        runWithMessage(() -> hrService.deleteAttendanceOverride(user, workDate), "일자별 예외를 삭제했습니다.", ra);
        return "redirect:/hr/attendance/policy";
    }

    @PostMapping("/hr/attendance/holiday")
    public String saveCompanyHoliday(@AuthenticationPrincipal CustomUserDetails user,
                                      @ModelAttribute HolidayForm form,
                                      RedirectAttributes ra) {
        runWithMessage(() -> hrService.saveCompanyHoliday(user, form), "회사 휴무일을 저장했습니다.", ra);
        return "redirect:/hr/attendance";
    }

    @PostMapping("/hr/attendance/holiday/delete")
    public String deleteCompanyHoliday(@AuthenticationPrincipal CustomUserDetails user,
                                        @RequestParam LocalDate holidayDate,
                                        RedirectAttributes ra) {
        runWithMessage(() -> hrService.deleteCompanyHoliday(user, holidayDate), "회사 휴무일을 삭제했습니다.", ra);
        return "redirect:/hr/attendance";
    }

    @GetMapping("/hr/attendance/correction/new")
    public String newAttendanceCorrection(@AuthenticationPrincipal CustomUserDetails user,
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @RequestParam(required = false) LocalDate date,
                                          Model model) {
        addCommon(model, user, "근태 정정 신청", "attendance");
        LocalDate today = LocalDate.now();
        model.addAttribute("attendanceRows", hrService.attendanceDailyStatuses(user, today.minusDays(60), today)
                .stream()
                .filter(s -> java.util.Objects.equals(s.getUserId(), user.getUserId()))
                .sorted((a, b) -> b.getWorkDate().compareTo(a.getWorkDate()))
                .toList());
        model.addAttribute("targetDate", date);
        addApprovalRouteModel(model, user, ApprovalDocumentType.ATTENDANCE_CORRECTION, null, null);
        return "hr/attendance-correction-new";
    }

    @PostMapping("/hr/attendance/correction/request")
    public String requestAttendanceCorrection(@AuthenticationPrincipal CustomUserDetails user,
                                              @ModelAttribute AttendanceCorrectionForm form,
                                              RedirectAttributes ra) {
        runWithMessage(() -> hrService.requestAttendanceCorrection(user, form), "근태 정정 신청이 등록되었습니다.", ra);
        return "redirect:/hr/attendance";
    }

    @PostMapping("/hr/attendance/correction/{id}/approve")
    public String approveAttendanceCorrection(@AuthenticationPrincipal CustomUserDetails user,
                                              @PathVariable Long id,
                                              @RequestParam(required = false) String password,
                                              @RequestParam(required = false, name = "back") String backParam,
                                              RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.ATTENDANCE_CORRECTION, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.approveAttendanceCorrection(user, id), "근태 정정을 승인했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/attendance/correction/{id}/reject")
    public String rejectAttendanceCorrection(@AuthenticationPrincipal CustomUserDetails user,
                                             @PathVariable Long id,
                                             @RequestParam(required = false) String reason,
                                             @RequestParam(required = false) String password,
                                             @RequestParam(required = false, name = "back") String backParam,
                                             RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.ATTENDANCE_CORRECTION, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.rejectAttendanceCorrection(user, id, reason), "근태 정정을 반려했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/attendance/correction/{id}/cancel")
    public String cancelAttendanceCorrection(@AuthenticationPrincipal CustomUserDetails user,
                                             @PathVariable Long id,
                                             @RequestParam(required = false) String back,
                                             RedirectAttributes ra) {
        runWithMessage(() -> hrService.cancelAttendanceCorrection(user, id), "근태 정정 신청을 회수했습니다.", ra);
        return cancelRedirect(back, "/hr/attendance");
    }

    /** [UX17] 통합 화면으로 리다이렉트 — 출장 목록은 [출장] 탭. */
    @GetMapping("/hr/trips")
    public String trips(@RequestParam(required = false, name = "q") String query) {
        return "redirect:/hr/attendance?tab=trip"
                + (query != null ? "&tq=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) : "");
    }

    @GetMapping("/hr/trips/new")
    public String newTrip(@AuthenticationPrincipal CustomUserDetails user,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                          Model model) {
        addCommon(model, user, "출장 신청", "tripNew");
        // [UX40] 근태 정정 화면에서 '출장으로 처리' 진입 — 대상 일자 프리필
        model.addAttribute("prefillStart", start);
        model.addAttribute("prefillEnd", end == null ? start : end);
        addApprovalRouteModel(model, user, ApprovalDocumentType.TRIP, null, null);
        model.addAttribute("expenseTypes", com.portfolio.worksystem.model.BusinessTripExpenseType.values());
        return "hr/trip-new";
    }

    @PostMapping("/hr/trips/request")
    public String requestTrip(@AuthenticationPrincipal CustomUserDetails user,
                              @ModelAttribute BusinessTripForm form,
                              RedirectAttributes ra) {
        runWithMessage(() -> hrService.requestBusinessTrip(user, form), "출장 신청이 등록되었습니다.", ra);
        addDelegationPromptIfNeeded(user, form.getStartDate(), form.getEndDate(), "출장", ra);
        return "redirect:/hr/attendance?tab=trip";
    }

    @GetMapping("/hr/trips/receipts/{receiptId}")
    public ResponseEntity<Resource> tripReceipt(@AuthenticationPrincipal CustomUserDetails user,
                                                @PathVariable Long receiptId,
                                                @RequestParam(defaultValue = "false") boolean download) {
        try {
            HrService.StoredBusinessTripReceipt receipt = hrService.loadBusinessTripReceipt(user, receiptId);
            ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                    .filename(receipt.originalFilename(), java.nio.charset.StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(receipt.mediaType())
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(receipt.resource());
        } catch (BusinessException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /* ================= [UX47] 출장보고서 ================= */

    @GetMapping("/hr/trips/{tripId}/report/new")
    public String newTripReport(@AuthenticationPrincipal CustomUserDetails user,
                                @PathVariable Long tripId, Model model) {
        BusinessTrip trip = hrService.tripDocument(user, tripId);
        com.portfolio.worksystem.model.BusinessTripReport existing = hrService.tripReportForTrip(tripId);
        if (existing != null && (existing.isPending() || "APPROVED".equals(existing.getStatus().name()))) {
            return "redirect:/hr/trips/report/" + existing.getId() + "/document";
        }
        addCommon(model, user, "출장 보고서 작성", "tripNew");
        model.addAttribute("trip", trip);
        model.addAttribute("tripExpenses", hrService.tripExpenses(tripId));
        model.addAttribute("rejectedReport", existing); // 반려된 이전 보고서(재작성 안내)
        model.addAttribute("expenseTypes", com.portfolio.worksystem.model.BusinessTripExpenseType.values());
        addApprovalRouteModel(model, user, ApprovalDocumentType.TRIP_REPORT, trip.getStartDate(), trip.getEndDate());
        return "hr/trip-report-new";
    }

    @PostMapping("/hr/trips/{tripId}/report")
    public String requestTripReport(@AuthenticationPrincipal CustomUserDetails user,
                                    @PathVariable Long tripId,
                                    @ModelAttribute com.portfolio.worksystem.dto.TripReportForm form,
                                    @RequestParam(value = "files", required = false) List<org.springframework.web.multipart.MultipartFile> files,
                                    RedirectAttributes ra) {
        runWithMessage(() -> hrService.requestTripReport(user, tripId, form, files), "출장 보고서가 제출되었습니다.", ra);
        return "redirect:/hr/attendance?tab=trip";
    }

    @GetMapping("/hr/trips/report/{id}/document")
    public String tripReportDocument(@AuthenticationPrincipal CustomUserDetails user,
                                     @PathVariable Long id, Model model) {
        com.portfolio.worksystem.model.BusinessTripReport report = hrService.tripReportDocument(user, id);
        List<ApprovalStep> steps = approvalService.steps(ApprovalDocumentType.TRIP_REPORT, id);
        model.addAttribute("title", "출장 보고서");
        model.addAttribute("report", report);
        model.addAttribute("reportExpenses", hrService.tripReportExpenses(id));
        model.addAttribute("tripExpenses", hrService.tripExpenses(report.getTripId()));
        model.addAttribute("reportFiles", hrService.tripReportFiles(id));
        model.addAttribute("steps", steps);
        model.addAttribute("stepSignatures", signatureMap(steps));
        model.addAttribute("applicantName", report.getMemberName());
        model.addAttribute("applicantSignature", accountService.signatureFilenameForUser(report.getUserId()));
        model.addAttribute("createdAt", report.getCreatedAt());
        addDocActionModel(model, user, ApprovalDocumentType.TRIP_REPORT, id,
                report.getStatus() != null ? report.getStatus().name() : null);
        return "hr/trip-report-document";
    }

    @PostMapping("/hr/trips/report/{id}/approve")
    public String approveTripReport(@AuthenticationPrincipal CustomUserDetails user,
                                    @PathVariable Long id,
                                    @RequestParam(required = false) String password,
                                    @RequestParam(required = false, name = "back") String backParam,
                                    RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.TRIP_REPORT, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.approveTripReport(user, id), "출장 보고서를 승인했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/trips/report/{id}/reject")
    public String rejectTripReport(@AuthenticationPrincipal CustomUserDetails user,
                                   @PathVariable Long id,
                                   @RequestParam(required = false) String reason,
                                   @RequestParam(required = false) String password,
                                   @RequestParam(required = false, name = "back") String backParam,
                                   RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.TRIP_REPORT, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.rejectTripReport(user, id, reason), "출장 보고서를 반려했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/trips/report/{id}/cancel")
    public String cancelTripReport(@AuthenticationPrincipal CustomUserDetails user,
                                   @PathVariable Long id,
                                   @RequestParam(required = false) String back,
                                   RedirectAttributes ra) {
        runWithMessage(() -> hrService.cancelTripReport(user, id), "출장 보고서를 회수했습니다.", ra);
        return cancelRedirect(back, "/hr/attendance?tab=trip");
    }

    @GetMapping("/hr/trips/report/files/{fileId}")
    public ResponseEntity<Resource> tripReportFile(@AuthenticationPrincipal CustomUserDetails user,
                                                   @PathVariable Long fileId,
                                                   @RequestParam(defaultValue = "false") boolean download) {
        try {
            HrService.StoredBusinessTripReceipt stored = hrService.loadTripReportFile(user, fileId);
            ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                    .filename(stored.originalFilename(), java.nio.charset.StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(stored.mediaType())
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(stored.resource());
        } catch (BusinessException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/hr/trips/{id}/approve")
    public String approveTrip(@AuthenticationPrincipal CustomUserDetails user,
                              @PathVariable Long id,
                              @RequestParam(required = false) String password,
                              @RequestParam(required = false, name = "back") String backParam,
                              RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.TRIP, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.approveBusinessTrip(user, id), "출장 신청을 승인했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/trips/{id}/reject")
    public String rejectTrip(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             @RequestParam(required = false) String reason,
                             @RequestParam(required = false) String password,
                             @RequestParam(required = false, name = "back") String backParam,
                             RedirectAttributes ra) {
        String back = cancelRedirect(backParam, docPath(ApprovalDocumentType.TRIP, id));
        if (!passwordOk(user, password, ra)) return back;
        runWithMessage(() -> hrService.rejectBusinessTrip(user, id, reason), "출장 신청을 반려했습니다.", ra);
        return back;
    }

    @PostMapping("/hr/trips/{id}/cancel")
    public String cancelTrip(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             @RequestParam(required = false) String back,
                             RedirectAttributes ra) {
        runWithMessage(() -> hrService.cancelBusinessTrip(user, id), "출장 신청을 회수했습니다.", ra);
        return cancelRedirect(back, "/hr/attendance?tab=trip");
    }

    private void addCommon(Model model, CustomUserDetails user, String title, String active) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", title);
        model.addAttribute("active", active);
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
        model.addAttribute("showWeekControl", false);
        model.addAttribute("canManageHr", hrService.canManageHr(user));
        model.addAttribute("canViewMemberHrOverview", hrService.canViewMemberHrOverview(user));
        model.addAttribute("weeksWithData", List.of());
    }

    private void addApprovalRouteModel(Model model, CustomUserDetails user,
                                       ApprovalDocumentType type, LocalDate startDate, LocalDate endDate) {
        LocalDate fallback = LocalDate.now();
        model.addAttribute("approvalPreview", hrService.approvalPreview(
                user, type, startDate == null ? fallback : startDate, endDate == null ? fallback : endDate));
        String routeAlert = (startDate != null && endDate != null)
                ? hrService.approvalRouteAlert(user, type, startDate, endDate)
                : null;
        model.addAttribute("routeAlert", routeAlert);
        model.addAttribute("approvalRouteReady", routeAlert == null);
        model.addAttribute("applicantName", user.getName());
        model.addAttribute("applicantSignature", accountService.signatureFilenameForUser(user.getUserId()));
    }

    private void addWeekCommon(Model model, CustomUserDetails user, String title, String active, String weekKey, String currentPath) {
        addCommon(model, user, title, active);
        model.addAttribute("weekKey", weekKey);
        model.addAttribute("weekLabel", WeekUtils.getWeekLabel(weekKey));
        model.addAttribute("weekPeriod", WeekUtils.getWeekPeriod(weekKey));
        model.addAttribute("prevWeekKey", WeekUtils.shiftWeek(weekKey, -1));
        model.addAttribute("nextWeekKey", WeekUtils.shiftWeek(weekKey, 1));
        model.addAttribute("currentWeekKey", WeekUtils.getCurrentWeekKey());
        model.addAttribute("currentPath", currentPath);
        model.addAttribute("showWeekControl", true);
    }


    private List<AttendanceDailyStatus> filterAttendanceStatuses(List<AttendanceDailyStatus> records, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return records;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return records.stream()
                .filter(record -> contains(record.getMemberName(), normalized)
                        || contains(record.getTeamName(), normalized))
                .toList();
    }

    private List<AttendanceCorrection> filterAttendanceCorrections(List<AttendanceCorrection> corrections, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return corrections;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return corrections.stream()
                .filter(correction -> contains(correction.getMemberName(), normalized)
                        || contains(correction.getTeamName(), normalized)
                        || contains(correction.getReason(), normalized)
                        || contains(correction.getRejectedReason(), normalized)
                        || (correction.getStatus() != null && contains(correction.getStatus().getLabel(), normalized)))
                .toList();
    }

    private boolean contains(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private String normalizeContractorFilter(String contractor) {
        String normalized = StringUtils.hasText(contractor) ? contractor.trim().toLowerCase(Locale.ROOT) : "all";
        return switch (normalized) {
            case "regular", "contractor" -> normalized;
            default -> "all";
        };
    }

    private boolean matchesContractor(Boolean contractor, String filter) {
        return "all".equals(filter)
                || ("contractor".equals(filter) && Boolean.TRUE.equals(contractor))
                || ("regular".equals(filter) && !Boolean.TRUE.equals(contractor));
    }

    private String normalizeDocumentBox(String box) {
        String normalized = box == null ? "" : box.trim();
        return switch (normalized) {
            case "all", "pending", "requested", "completed", "sent", "received", "after", "ioall" -> normalized;
            default -> "all";
        };
    }

    /** 사이드 메뉴 그룹 분류: 결재 문서함(approval) / 발수신 문서함(io). */
    private String documentGroup(String box) {
        return switch (box) {
            case "sent", "received", "ioall" -> "io";
            default -> "approval";
        };
    }

    private String documentBoxTitle(String box) {
        return switch (box) {
            case "pending" -> "결재 요청 문서함";
            case "requested" -> "결재 대기 문서";
            case "completed" -> "결재 완료 문서";
            case "sent" -> "발신 문서함";
            case "received" -> "수신 문서함";
            case "after" -> "후결 문서함";
            case "ioall" -> "발·수신 문서함";
            default -> "결재 문서함";
        };
    }

    private List<DocumentBox> documentBoxes() {
        // 결재 문서함 그룹 [전체][대기][완료][요청][후결]
        return List.of(
                new DocumentBox("all", "전체"),
                new DocumentBox("requested", "대기"),
                new DocumentBox("completed", "완료"),
                new DocumentBox("pending", "요청"),
                new DocumentBox("after", "후결")
        );
    }

    private List<DocumentBox> ioBoxes() {
        // 발·수신 문서함 그룹 [전체][발신][수신]
        return List.of(
                new DocumentBox("ioall", "전체"),
                new DocumentBox("sent", "발신"),
                new DocumentBox("received", "수신")
        );
    }

    private record DocumentBox(String key, String label) {
    }

    private Map<Long, List<ApprovalStep>> leaveStepsByRequest(List<LeaveRequest> requests) {
        Map<Long, List<ApprovalStep>> result = new LinkedHashMap<>();
        for (LeaveRequest request : requests) {
            result.put(request.getId(), hrService.leaveApprovalSteps(request.getId()));
        }
        return result;
    }

    private Map<Long, List<ApprovalStep>> flexStepsByRequest(List<FlexTimeRequest> requests) {
        Map<Long, List<ApprovalStep>> result = new LinkedHashMap<>();
        for (FlexTimeRequest request : requests) {
            result.put(request.getId(), hrService.flexTimeApprovalSteps(request.getId()));
        }
        return result;
    }

    private Map<Long, List<ApprovalStep>> correctionStepsByRequest(List<AttendanceCorrection> corrections) {
        Map<Long, List<ApprovalStep>> result = new LinkedHashMap<>();
        for (AttendanceCorrection correction : corrections) {
            result.put(correction.getId(), hrService.attendanceCorrectionApprovalSteps(correction.getId()));
        }
        return result;
    }

    private Map<Long, List<ApprovalStep>> tripStepsByRequest(List<BusinessTrip> trips) {
        Map<Long, List<ApprovalStep>> result = new LinkedHashMap<>();
        for (BusinessTrip trip : trips) {
            result.put(trip.getId(), hrService.tripApprovalSteps(trip.getId()));
        }
        return result;
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
