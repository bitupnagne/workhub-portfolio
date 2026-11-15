package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.LeaveAllowanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 연차 원장에서 직급별 정산 단가를 저장한다. */
@RequiredArgsConstructor
@Controller
public class LeaveAllowanceController {
    private final LeaveAllowanceService leaveAllowanceService;

    @PostMapping("/hr/leave/ledger/allowance")
    public String saveLedgerAllowance(@AuthenticationPrincipal CustomUserDetails user,
                                      @RequestParam int year,
                                      @RequestParam Long gradeId,
                                      @RequestParam(required = false) String amount,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "1") int apage,
                                      RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message", leaveAllowanceService.saveRate(user, year, gradeId, amount));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        int returnYear = year >= 2000 && year <= leaveAllowanceService.currentYear() + 1
                ? year : leaveAllowanceService.currentYear();
        return "redirect:/hr/leave/ledger?year=" + returnYear
                + "&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)
                + "&page=" + Math.max(1, page) + "&apage=" + Math.max(1, apage);
    }

}
