package com.portfolio.worksystem.config;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.SystemSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 메뉴 노출 정책 직접 URL 접근 차단.
 * - 슈퍼관리자가 '메뉴 노출 관리'에서 숨긴 메뉴의 경로는 nav 숨김뿐 아니라
 *   주소창 직접 입력도 접근하지 못하도록 홈(대시보드, 대시보드도 숨김이면 런처)으로 되돌린다.
 * - [UX50] 내부망 정책도 지원. 단, 내부망에서 슈퍼관리자는 정책의 영향을 받지 않아
 *   설정 화면이 스스로 잠기는 사고를 방지한다(외부망은 기존대로 전원 적용).
 */
@Configuration
@RequiredArgsConstructor
public class ExternalMenuGuardConfig implements WebMvcConfigurer {

    private final SystemSettingsService systemSettingsService;
    private final ReleaseFeatures releaseFeatures;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                boolean external = AccessZone.isExternal(request);
                if (!external && isSuperAdmin()) {
                    return true; // [UX50] 내부망 슈퍼관리자는 잠금 방지 위해 항상 통과
                }
                String ctx = request.getContextPath() == null ? "" : request.getContextPath();
                String uri = new org.springframework.web.util.UrlPathHelper().getPathWithinApplication(request);
                if (systemSettingsService.isBlockedPath(external, uri, request.getParameter("box"))) {
                    try {
                        String home = releaseFeatures.getHomeUrl();
                        response.sendRedirect(ctx + home);
                    } catch (Exception ignore) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    }
                    return false;
                }
                return true;
            }
        });
    }

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CustomUserDetails u && u.isSuperAdmin();
    }
}
