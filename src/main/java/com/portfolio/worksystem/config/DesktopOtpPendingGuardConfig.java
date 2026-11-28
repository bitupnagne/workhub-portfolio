package com.portfolio.worksystem.config;

import com.portfolio.worksystem.service.DesktopAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * [보안] 데스크톱 OTP 서버측 강제.
 * 데스크톱 로그인/자동로그인 시 OTP 필요 계정이면 세션이 {@link DesktopAuthService#OTP_PENDING_SESSION}=true 로 표시된다.
 * 이 상태에서는 OTP 등록/검증 엔드포인트와 최소 정적/시스템 리소스 외의 모든 요청을 차단(API=401)한다.
 * verify/enroll 성공(DesktopApiController)에서 플래그를 내려 즉시 통과된다.
 *
 * 웹 로그인은 SecurityConfig가 SecurityContext를 비워 차단하므로 이 게이트의 영향을 받지 않는다.
 * SSO(웹 열기) 세션은 OTP 통과 후 생성되어 플래그가 없으므로 차단되지 않는다.
 */
@Configuration
public class DesktopOtpPendingGuardConfig implements WebMvcConfigurer {

    /** OTP 검증 전에도 허용하는 경로(OTP 처리 + 로그아웃 + 정적/시스템). */
    private static final String[] ALLOW_PREFIX = {
            "/api/desktop/otp/",      // state / setup / enroll / verify
            "/api/desktop/login", "/api/desktop/refresh", "/api/desktop/logout",  // 인증 진입점(스스로 게이트 설정)
            "/logout", "/login", "/error",
            "/css/", "/js/", "/img/", "/otp-ui/", "/vendor/", "/webjars/",
            "/system-logo", "/system-theme.css", "/favicon",
            "/api/system/",           // theme / branding (앱 시작 표시용)
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                HttpSession session = request.getSession(false);
                if (session == null || !Boolean.TRUE.equals(session.getAttribute(DesktopAuthService.OTP_PENDING_SESSION))) {
                    return true;
                }
                String ctx = request.getContextPath() == null ? "" : request.getContextPath();
                String uri = request.getRequestURI();
                if (!ctx.isEmpty() && uri.startsWith(ctx)) {
                    uri = uri.substring(ctx.length());
                }
                for (String p : ALLOW_PREFIX) {
                    if (uri.equals(p) || uri.startsWith(p)) {
                        return true;
                    }
                }
                // OTP 미검증 데스크톱 세션 — 보호 리소스 차단.
                // 페이지도 401로 응답한다. (세션이 인증 상태라 /login 리다이렉트는 /dashboard로 되튕겨 무한 루프가 됨.
                //  데스크톱은 OTP 통과 전 웹 페이지를 열지 않으므로 이 경로는 비정상 접근 → 401이 적절.)
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        });
    }
}
