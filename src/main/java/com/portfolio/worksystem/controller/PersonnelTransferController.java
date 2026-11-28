package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.EmpGradeService;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.PersonnelTransferService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 인사 관리 — 인사이동 화면/이력(인사 담당자 권한). */
@Controller
@RequiredArgsConstructor
public class PersonnelTransferController {

    private final PersonnelTransferService transferService;
    private final OrganizationService organizationService;
    private final EmpGradeService empGradeService;
    private final TeamMapper teamMapper;

    private void addCommon(Model model, CustomUserDetails user) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", "인사이동");
        model.addAttribute("active", "personnelTransfer");
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
    }

    private void requireManager(CustomUserDetails user) {
        if (!empGradeService.canManageGrades(user)) {
            throw new BusinessException("인사 관리 권한이 없습니다.");
        }
    }

    @GetMapping("/admin/personnel/transfer")
    public String page(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam(required = false) Integer year, Model model) {
        requireManager(user);
        addCommon(model, user);
        int y = year != null ? year : LocalDate.now().getYear();
        model.addAttribute("members", organizationService.adminManagedUsers());
        model.addAttribute("teams", teamMapper.findAll());
        model.addAttribute("history", transferService.history(y));
        model.addAttribute("selectedYear", y);
        List<Integer> years = new ArrayList<>();
        for (int i = 0; i < 4; i++) years.add(LocalDate.now().getYear() - i);
        model.addAttribute("years", years);
        return "admin/personnel-transfer";
    }

    @PostMapping("/admin/personnel/transfer")
    public String transfer(@AuthenticationPrincipal CustomUserDetails user,
                           @RequestParam Long userId,
                           @RequestParam Long toTeamId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
                           RedirectAttributes ra) {
        try {
            transferService.transfer(userId, toTeamId, effectiveDate, user);
            ra.addFlashAttribute("message", "인사이동 발령을 처리했습니다.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/personnel/transfer?year=" + effectiveDate.getYear();
    }

    @PostMapping("/admin/personnel/transfer/{id}/date")
    public String updateDate(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate,
                             RedirectAttributes ra) {
        try {
            transferService.updateDate(id, effectiveDate, user);
            ra.addFlashAttribute("message", "발령일을 수정했습니다.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/personnel/transfer?year=" + effectiveDate.getYear();
    }

    @PostMapping("/admin/personnel/transfer/{id}/delete")
    public String delete(@AuthenticationPrincipal CustomUserDetails user,
                         @PathVariable Long id,
                         @RequestParam(required = false) String confirmText,
                         @RequestParam(required = false) Integer year,
                         RedirectAttributes ra) {
        try {
            transferService.delete(id, confirmText, user);
            ra.addFlashAttribute("message", "인사이동 이력을 삭제했습니다.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/personnel/transfer?year=" + (year == null ? LocalDate.now().getYear() : year);
    }
}
