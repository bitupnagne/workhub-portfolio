package com.portfolio.worksystem.config;

import com.portfolio.worksystem.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * [UX35b] 초기/재설정 비밀번호 강제 변경 — 서버 측 강제.
 * 로그인 시점 principal 플래그가 켜져 있으면 비밀번호 변경/로그아웃/정적자원 외의
 * 모든 요청을 마이페이지(강제 변경 모달)로 되돌린다. JS 모달 우회·API 직접호출을 막는다.
 * 변경 성공 시 principal 플래그가 내려가(MyPageController) 즉시 통과된다.
 */
@Configuration
public class MustChangePasswordGuardConfig implements WebMvcConfigurer {

    /** 화면 표시와 로그인 세션 생존 확인에만 필요한 정확한 경로. */
    private static final Set<String> ALLOW_EXACT = Set.of(
            "/mypage", "/api/session/ping",
            "/api/desktop/login", "/api/desktop/refresh", "/api/desktop/logout",
            "/api/desktop/otp/state", "/api/desktop/otp/setup",
            "/api/desktop/otp/enroll", "/api/desktop/otp/verify", "/api/desktop/password"
    );

    /** 강제 상태에서도 허용하는 경로(비밀번호 변경 자체 + 로그아웃 + 정적/에러). */
    private static final String[] ALLOW_PREFIX = {
            "/mypage/password", "/logout", "/login", "/error",
            "/css/", "/js/", "/img/", "/otp-ui/", "/vendor/", "/webjars/",
            "/system-logo", "/system-theme.css", "/profile-images/", "/signature-images/",
            "/favicon", "/api/system/",
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails user) || !user.isMustChangePassword()) {
                    return true;
                }
                String ctx = request.getContextPath() == null ? "" : request.getContextPath();
                String uri = request.getRequestURI();
                if (!ctx.isEmpty() && uri.startsWith(ctx)) {
                    uri = uri.substring(ctx.length());
                }
                if (isAllowedPath(uri)) {
                    return true;
                }
                boolean ajax = "fetch".equals(request.getHeader("X-Requested-With")) || uri.startsWith("/api/");
                try {
                    if (ajax) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    } else {
                        response.sendRedirect(ctx + "/mypage");
                    }
                } catch (Exception ignore) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                }
                return false;
            }
        });
    }

    static boolean isAllowedPath(String uri) {
        if (ALLOW_EXACT.contains(uri)) {
            return true;
        }
        for (String prefix : ALLOW_PREFIX) {
            if (uri.equals(prefix) || uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
