package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.CommunicationService;
import com.portfolio.worksystem.service.DeploySsoService;
import com.portfolio.worksystem.service.HrService;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RequiredArgsConstructor
@ControllerAdvice
public class CommunicationModelAdvice {
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;
    private final CommunicationService communicationService;
    private final OrganizationService organizationService;
    private final HrService hrService;
    private final DeploySsoService deploySsoService;
    private final SystemSettingsService systemSettingsService;

    @ModelAttribute
    public void addCommunicationCounts(Model model) {
        model.addAttribute("releaseFeatures", releaseFeatures);
        model.addAttribute("homeUrl", releaseFeatures.getHomeUrl());
        if (isApiRequest()) {
            return;
        }
        boolean external = isExternalRequest();
        model.addAttribute("externalZone", external);   // [B] DMZ 외부 접속 여부 — 민감 메뉴 숨김에 사용
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // [UX50] 접속 존별 숨김 메뉴(사이드바 공용). 내부망 슈퍼관리자는 잠금 방지를 위해 정책 미적용.
        boolean superAdmin = authentication != null
                && authentication.getPrincipal() instanceof CustomUserDetails su && su.isSuperAdmin();
        java.util.Set<String> hidden = new java.util.HashSet<>(external ? systemSettingsService.externalHiddenMenus()
                : (superAdmin ? java.util.Set.of() : systemSettingsService.internalHiddenMenus()));
        model.addAttribute("personnelCardsAllowed", (!external && superAdmin)
                || !systemSettingsService.isBlockedPath(external, "/people/me", null));
        hidden.addAll(releaseFeatures.unavailableMenus());
        model.addAttribute("hiddenMenus", hidden);
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails user)) {
            model.addAttribute("unreadNotificationCount", 0);
            model.addAttribute("unreadMessageCount", 0);
            model.addAttribute("currentUserOrgRole", null);
            model.addAttribute("canViewMemberHrOverview", false);
            model.addAttribute("canManageHrGlobal", false);
            model.addAttribute("deploySsoLinked", false);
            model.addAttribute("mustChangePassword", false);
            return;
        }
        // 매 페이지 요청마다 본인 표시 정보(이름/사진/소속)를 DB 최신값으로 갱신한다.
        UserAccount fresh = communicationService.userBrief(user.getUserId());
        user.syncDisplay(fresh);
        // [UX35] 초기/재설정 비밀번호 사용 중 — 전 화면에서 변경 모달 강제(fragments/app)
        model.addAttribute("mustChangePassword",
                fresh != null && Boolean.TRUE.equals(fresh.getMustChangePassword()));
        model.addAttribute("unreadNotificationCount", communicationService.unreadNotificationCount(user));
        model.addAttribute("unreadMessageCount", communicationService.unreadMessageCount(user));
        model.addAttribute("currentUserOrgRole", organizationService.orgRoleLabels().get(user.getUserId()));
        // [N-7] 사이드 메뉴의 [직원 HR 관리] 그룹 노출 여부를 모든 페이지에서 일관되게 제공.
        model.addAttribute("canViewMemberHrOverview", releaseFeatures.isAttendanceEnabled() && hrService.canViewMemberHrOverview(user));
        // 사이드 메뉴 [근태 설정] 노출 — HR 담당자만
        model.addAttribute("canManageHrGlobal", releaseFeatures.isAttendanceEnabled() && hrService.canManageHr(user));
        model.addAttribute("deploySsoLinked", releaseFeatures.isDeployEnabled() && deploySsoService.findActive(user.getUserId()) != null);
        // [Q-3] 본부장 여부 — /report/admin 메뉴 노출 및 데이터 범위 결정에 사용.
        model.addAttribute("isDivisionLeader", hrService.isDivisionLeader(user));
        // [OTP] 2차 인증은 로그인 화면(pre-auth) 모달로 처리한다. 인증된 세션은 이미 OTP를 통과한 상태이므로
        //       인앱 차단 모달은 더 이상 사용하지 않는다.
    }

    private boolean isApiRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRequestURI().startsWith("/api/");
        }
        return false;
    }

    /** 외부 접속 여부(DMZ 헤더 + 실 클라이언트 IP; 사내망 헤어핀은 내부 처리). */
    private boolean isExternalRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return com.portfolio.worksystem.config.AccessZone.isExternal(attributes.getRequest());
        }
        return false;
    }
}
