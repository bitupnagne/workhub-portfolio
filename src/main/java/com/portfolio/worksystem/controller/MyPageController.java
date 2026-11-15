package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.PasswordChangeForm;
import com.portfolio.worksystem.dto.ProfileInfoForm;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.AccountService;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.DeploySsoService;
import com.portfolio.worksystem.service.OrganizationService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@RequiredArgsConstructor
@Controller
public class MyPageController {
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;
    private final AccountService accountService;
    private final OrganizationService organizationService;
    private final DeploySsoService deploySsoService;
    private final com.portfolio.worksystem.service.EmpGradeService empGradeService;
    private final com.portfolio.worksystem.service.HrService hrService;

    @GetMapping("/mypage")
    public String myPage(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user);
        model.addAttribute("passwordChangeForm", new PasswordChangeForm("", "", ""));
        model.addAttribute("profileInfoForm", new ProfileInfoForm(user.getDept(), user.getExtensionNumber(), user.getMobileNumber(), user.getJobTitle(), user.getEmail()));
        model.addAttribute("signatureFilename", accountService.signatureFilename(user));
        model.addAttribute("deploySsoLink", releaseFeatures.isDeployEnabled() ? deploySsoService.findActive(user.getUserId()) : null);
        // 등급은 관리자만 변경 가능 — 본인은 조회만. 세션이 아닌 DB 최신값을 보여준다.
        model.addAttribute("myGradeName", empGradeService.gradeNameOfUser(user.getUserId()));
        // [UX31/32] 본인 인사카드 대신 마이페이지에서 확인하는 인사 요약(연차 잔여)
        model.addAttribute("myRemainingLeave",
                releaseFeatures.isAttendanceEnabled()
                        ? hrService.remainingLeaveDays(user.getUserId(), java.time.LocalDate.now().getYear()).stripTrailingZeros().toPlainString() : null);
        return "account/mypage";
    }

    /** [UX34] 비밀번호 변경 모달 — 현재 비밀번호 사전 검증(AJAX). */
    @PostMapping("/mypage/password/verify")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Boolean> verifyCurrentPassword(@AuthenticationPrincipal CustomUserDetails user,
                                                                @RequestParam("currentPassword") String currentPassword) {
        return java.util.Map.of("ok", accountService.matchesPassword(user.getUserId(), currentPassword));
    }

    @PostMapping("/mypage/deploy-sso/link")
    public String linkDeploySso(@AuthenticationPrincipal CustomUserDetails user,
                                @RequestParam("code") String code,
                                RedirectAttributes ra) {
        try {
            deploySsoService.link(user, code);
            ra.addFlashAttribute("message", "배포시스템 SSO 연동이 완료되었습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mypage";
    }

    @PostMapping("/mypage/deploy-sso/unlink")
    public String unlinkDeploySso(@AuthenticationPrincipal CustomUserDetails user,
                                  RedirectAttributes ra) {
        deploySsoService.unlink(user);
        ra.addFlashAttribute("message", "배포시스템 SSO 연동을 해제했습니다.");
        return "redirect:/mypage";
    }

    @PostMapping("/deploy-system/sso")
    public String moveDeploySystem(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam(value = "redirect", required = false) String redirect,
                                   HttpSession session,
                                   RedirectAttributes ra) {
        if (!user.isOtpEnabled() || !Boolean.TRUE.equals(session.getAttribute(OtpController.OTP_VERIFIED))) {
            ra.addFlashAttribute("error", "배포시스템 이동에는 오피스시스템 OTP 인증이 필요합니다. OTP를 등록·인증한 뒤 다시 시도해 주세요.");
            return "redirect:/mypage";
        }
        try {
            return "redirect:" + deploySsoService.issueLoginUrl(user, redirect);
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/mypage";
        }
    }

    @GetMapping("/deploy-system/sso")
    public String openDeploySystem(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam(value = "redirect", required = false) String redirect,
                                   HttpSession session,
                                   RedirectAttributes ra) {
        return moveDeploySystem(user, redirect, session, ra);
    }

    @PostMapping("/mypage/basic-info")
    public String updateBasicInfo(@AuthenticationPrincipal CustomUserDetails user,
                                  @Valid @ModelAttribute ProfileInfoForm form,
                                  BindingResult bindingResult,
                                  RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", bindingResult.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/mypage";
        }
        try {
            accountService.updateBasicInfo(user, form);
            ra.addFlashAttribute("message", "기본정보가 저장되었습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mypage";
    }

    @PostMapping("/mypage/password")
    public String changePassword(@AuthenticationPrincipal CustomUserDetails user,
                                 @Valid @ModelAttribute PasswordChangeForm form,
                                 BindingResult bindingResult,
                                 RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("error", bindingResult.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/mypage";
        }
        try {
            accountService.changePassword(user, form);
            user.markPasswordChanged(); // [UX35b] 세션 principal 플래그 해제 — 강제 가드 통과
            ra.addFlashAttribute("message", "비밀번호가 변경되었습니다. 다음 로그인부터 새 비밀번호를 사용하세요.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mypage";
    }

    @PostMapping("/mypage/profile-image")
    public String updateProfileImage(@AuthenticationPrincipal CustomUserDetails user,
                                     @RequestParam("profileImage") MultipartFile profileImage,
                                     RedirectAttributes ra) {
        try {
            accountService.updateProfileImage(user, profileImage);
            ra.addFlashAttribute("message", "프로필 사진이 저장되었습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mypage";
    }

    @PostMapping("/mypage/profile-image/delete")
    public String deleteProfileImage(@AuthenticationPrincipal CustomUserDetails user,
                                     RedirectAttributes ra) {
        try {
            accountService.deleteProfileImage(user);
            ra.addFlashAttribute("message", "프로필 사진을 삭제했습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mypage";
    }

    @PostMapping("/mypage/signature")
    public String updateSignature(@AuthenticationPrincipal CustomUserDetails user,
                                  @RequestParam("signature") MultipartFile signature,
                                  RedirectAttributes ra) {
        try {
            accountService.updateSignatureImage(user, signature);
            ra.addFlashAttribute("message", "서명이 저장되었습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mypage";
    }

    @PostMapping("/mypage/signature/draw")
    public String updateSignatureDraw(@AuthenticationPrincipal CustomUserDetails user,
                                      @RequestParam("dataUrl") String dataUrl,
                                      RedirectAttributes ra) {
        try {
            accountService.updateSignatureFromDataUrl(user, dataUrl);
            ra.addFlashAttribute("message", "서명이 저장되었습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mypage";
    }

    @PostMapping("/mypage/signature/delete")
    public String deleteSignature(@AuthenticationPrincipal CustomUserDetails user,
                                  RedirectAttributes ra) {
        try {
            accountService.deleteSignature(user);
            ra.addFlashAttribute("message", "서명을 삭제했습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/mypage";
    }

    private void addCommon(Model model, CustomUserDetails user) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", "마이페이지");
        model.addAttribute("active", "mypage");
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
    }
}
