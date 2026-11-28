package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.PayrollService;
import java.time.LocalDate;
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

/** [PAY] 급여 관리 — 직급별 통상시급(적용일별 이력). 들어오는 권한은 '급여 관리' 메뉴 권한이다. */
@Controller
@RequiredArgsConstructor
public class PayrollController {
    private final PayrollService payrollService;
    private final OrganizationService organizationService;

    @GetMapping("/admin/payroll")
    public String payroll(@AuthenticationPrincipal CustomUserDetails user,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          Model model) {
        LocalDate base = date == null ? payrollService.today() : date;
        model.addAttribute("currentUser", user);
        model.addAttribute("title", "급여 관리");
        model.addAttribute("active", "payroll");
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
        var rows = payrollService.rows(base);
        model.addAttribute("rows", rows);
        model.addAttribute("baseDate", base);
        model.addAttribute("today", payrollService.today());
        model.addAttribute("dailyHours", payrollService.dailyStandardHours());
        model.addAttribute("missingCount", rows.stream().filter(PayrollService.Row::isMissing).count());
        model.addAttribute("missingPeople", rows.stream().filter(PayrollService.Row::isMissing)
                .mapToInt(row -> row.grade().getUserCount() == null ? 0 : row.grade().getUserCount()).sum());
        return "admin/payroll";
    }

    @PostMapping("/admin/payroll/wage")
    public String saveWage(@AuthenticationPrincipal CustomUserDetails user,
                           @RequestParam Long gradeId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
                           @RequestParam(required = false) String hourlyWage,
                           @RequestParam(required = false) String note,
                           RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message", payrollService.saveWage(user, gradeId, effectiveFrom, hourlyWage, note));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/payroll";
    }

    @PostMapping("/admin/payroll/wage/{id}/delete")
    public String deleteWage(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message", payrollService.deleteWage(user, id));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/payroll";
    }
}
