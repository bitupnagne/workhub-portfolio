package com.portfolio.worksystem.config;

import com.portfolio.worksystem.security.CustomUserDetails;
import jakarta.servlet.http.HttpSessionListener;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@RequiredArgsConstructor
@EnableWebSecurity
@EnableMethodSecurity
@Configuration
public class SecurityConfig {
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;

    @org.springframework.beans.factory.annotation.Value("${app.portfolio.public-demo:${PORTFOLIO_PUBLIC_DEMO:false}}")
    private boolean publicDemo;

    @org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.name:OFFICESESSION}")
    private String sessionCookieName;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    HttpSessionListener httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    // [B-6] 정적 리소스를 Spring Security 체인에서 완전히 제외한다.
    //  - 효과 1: Security가 자동으로 붙이는 Cache-Control: no-store 헤더가 안 붙어
    //           ResourceHttpRequestHandler의 ETag + maxAge가 정상 작동.
    //  - 효과 2: 요청당 SecurityContext/CSRF 체크 비용 절감.
    @Bean
    WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers(
                "/css/**", "/js/**", "/img/**", "/otp-ui/**", "/vendor/**",
                "/favicon.ico",
                // PWA: 매니페스트/서비스워커/오프라인 폴백(무인증 정적)
                "/manifest.webmanifest", "/sw.js", "/offline.html"
        );
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                           SessionRegistry sessionRegistry,
                                           SessionAuthenticationStrategy verifiedForceWebLoginStrategy,
                                           com.portfolio.worksystem.service.SystemSettingsService settings,
                                           com.portfolio.worksystem.security.LoginAttemptService loginAttemptService) throws Exception {
        http
                .authorizeHttpRequests(auth -> {
                    if (publicDemo) auth.requestMatchers(PortfolioPublicDemoPolicy::isRestrictedRequest).denyAll();
                    auth.requestMatchers("/login", "/error", "/system-theme.css", "/system-logo/**", "/api/system/theme", "/api/system/branding").permitAll()
                        // 로그인 2차 인증(pre-auth) 단계 — 코드 통과 전까지는 미인증 상태에서 접근.
                        .requestMatchers("/login/otp/**").permitAll()
                        .requestMatchers("/profile-images/**", "/signature-images/**", "/notice-images/**", "/system-logos/**").permitAll()
                        // [A-5] 헬스체크/info는 무인증 — 로드밸런서/모니터링용. metrics는 인증 필요.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/desktop/login", "/api/desktop/refresh", "/api/desktop/releases/**", "/desktop/sso/**").permitAll()
                        .requestMatchers("/api/internal/deploy/**").permitAll()
                        .requestMatchers("/people").hasAnyRole("SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN")
                        // 이 포트폴리오에서 제공하지 않는 기존 기능의 주소는 모든 역할에서 차단한다.
                        .requestMatchers("/eval/**", "/admin/eval/**", "/admin/grades/**",
                                "/admin/personnel/transfer/**", "/hr/performance/**",
                                "/api/desktop/eval/**", "/admin/org/kpi-target").denyAll()
                        .requestMatchers("/admin/system/**", "/admin/releases/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/admin/settings/**").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/admin/org/**", "/admin/users/**").hasAnyRole("SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN")
                        .requestMatchers("/admin/approval-routes/**").hasAnyRole("SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN")
                        .requestMatchers("/admin/notices/**").hasAnyRole("SUPER_ADMIN", "SYSTEM_ADMIN", "NOTICE_ADMIN")
                        // [감사] URL 규칙 누락분 보강 — HR 운영점검도 관리자 롤 필수(컨트롤러 가드와 이중 방어)
                        .requestMatchers("/admin/hr-diagnostics/**").hasAnyRole("SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN")
                        // [Q-2] /report/team GET은 누구나 view, POST 권한은 컨트롤러/서비스 단에서 체크
                        .anyRequest().authenticated();
                })
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/desktop/**", "/api/internal/deploy/**")
                )
                // [E403] 서버 재시작으로 세션·CSRF가 무효화된 뒤의 일반 폼 제출은
                //        Whitelabel 403 대신 로그인 화면으로 돌린다(안내 배너 relogin=1).
                //        AJAX(fetch)·API 는 403 유지(soft-submit forceLogout 이 처리),
                //        실제 권한 부족 403 은 그대로 → 전용 에러 페이지(error/403)가 렌더.
                .exceptionHandling(ex -> ex.accessDeniedHandler((request, response, denied) -> {
                    boolean csrfDenied = denied instanceof org.springframework.security.web.csrf.CsrfException;
                    boolean ajax = "fetch".equals(request.getHeader("X-Requested-With"))
                            || request.getRequestURI().startsWith("/api/");
                    if (publicDemo && PortfolioPublicDemoPolicy.isRestrictedRequest(request)) {
                        if (ajax) {
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"success\":false,\"message\":\"" + PortfolioPublicDemoPolicy.MESSAGE + "\"}");
                        } else {
                            response.sendRedirect(request.getContextPath() + "/demo-restricted");
                        }
                        return;
                    }
                    if (csrfDenied && !ajax) {
                        response.sendRedirect("/login?relogin=1");
                        return;
                    }
                    response.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                }))
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> {
                            loginAttemptService.reset(authentication.getName());
                            // [보안] 비밀번호만 통과한 단계 — OTP가 요구되면 '로그인 완료(인증)'를 보류한다.
                            //  Spring SecurityContext 를 세션에서 제거해 미인증 상태로 되돌리고,
                            //  로그인 화면 OTP 모달로 보낸다. OTP 통과(/login/otp/verify) 시에만 재인증.
                            //  → 미인증이므로 모든 보호 페이지/ API 는 표준 인가가 자동 차단(F12·콘솔 우회 불가).
                            if (authentication.getPrincipal() instanceof CustomUserDetails u) {
                                // 외부망 OTP 강제는 관리자 정책(otp.require.external, 기본 ON)으로 켜고 끔.
                                boolean external = AccessZone.isExternal(request) && settings.otpRequireExternal();
                                boolean otpRequired = external || u.isOtpEnabled()
                                        || settings.otpRequireUser()
                                        // 관리자 OTP 필수 정책: 시스템관리자뿐 아니라 관리자 롤 보유자 전체에 적용
                                        || (settings.otpRequireAdmin() && u.hasAnyAdminRole());
                                if (!publicDemo && otpRequired) {
                                    jakarta.servlet.http.HttpSession s = request.getSession();
                                    s.setAttribute(com.portfolio.worksystem.controller.OtpController.OTP_PENDING_UID, u.getUserId());
                                    s.setAttribute(com.portfolio.worksystem.controller.OtpController.OTP_PENDING_NAME, u.getUsername());
                                    s.setAttribute(com.portfolio.worksystem.controller.OtpController.OTP_PENDING_ENROLLED, u.isOtpEnabled());
                                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                                    s.removeAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                                    response.sendRedirect("/login?otp=1");
                                    return;
                                }
                            }
                            response.sendRedirect(releaseFeatures.getHomeUrl());
                        })
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof SessionAuthenticationException) {
                                String username = request.getParameter("username");
                                String encoded = username == null ? "" : URLEncoder.encode(username, StandardCharsets.UTF_8);
                                response.sendRedirect("/login?conflict=true&username=" + encoded);
                                return;
                            }
                            if (exception instanceof org.springframework.security.authentication.LockedException) {
                                response.sendRedirect("/login?locked=true");
                                return;
                            }
                            loginAttemptService.recordFailure(request.getParameter("username"));
                            response.sendRedirect("/login?error=true");
                        })
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout=true")
                        .invalidateHttpSession(true)
                        .deleteCookies(sessionCookieName)
                )
                .sessionManagement(session -> session
                        .addSessionAuthenticationStrategy(verifiedForceWebLoginStrategy)
                        .maximumSessions(publicDemo ? -1 : 1)
                        .sessionRegistry(sessionRegistry)
                        .maxSessionsPreventsLogin(true)
                        .expiredUrl("/login?expired=true")
                )
                .headers(headers -> headers
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        // 동일 출처 iframe 허용 — 근거자료 PDF 뷰어(iframe inline)용. 기본값 DENY면 "연결 거부".
                        .frameOptions(fo -> fo.sameOrigin())
                        // XSS 심층방어(CSP). 인라인 스크립트/스타일·에디터(toastui/quill)·웹소켓·blob 미리보기를
                        // 위해 script 'unsafe-inline'/'unsafe-eval', img/media blob: 를 허용(자기 출처 한정).
                        // frame-ancestors 'self'(클릭재킹)·object-src 'none'·base-uri 'self' 는 차단.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
                                + "style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data: blob:; "
                                + "media-src 'self' blob:; "
                                + "font-src 'self' data:; "
                                + "connect-src 'self' ws: wss:; "
                                + "object-src 'none'; "
                                + "base-uri 'self'; "
                                + "frame-ancestors 'self'"))
                );
        return http.build();
    }

    @Bean
    SessionAuthenticationStrategy verifiedForceWebLoginStrategy(SessionRegistry sessionRegistry) {
        // 인증 성공 뒤에만 실행하고, 기본 동시 세션 검사·세션 ID 변경·등록 순서는 유지한다.
        return (authentication, request, response) -> {
            if (!publicDemo && "POST".equalsIgnoreCase(request.getMethod())
                    && "/login".equals(request.getServletPath())
                    && "true".equalsIgnoreCase(request.getParameter("force"))) {
                expireWebSessions(sessionRegistry, authentication.getName());
            }
        };
    }

    private AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> {
            response.sendRedirect(releaseFeatures.getHomeUrl());
        };
    }

    private void expireWebSessions(SessionRegistry sessionRegistry, String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String normalized = username.trim();
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!normalized.equals(principalUsername(principal))) {
                continue;
            }
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                session.expireNow();
            }
        }
    }

    private String principalUsername(Object principal) {
        if (principal instanceof CustomUserDetails user) {
            return user.getUsername();
        }
        if (principal instanceof UserDetails user) {
            return user.getUsername();
        }
        return principal == null ? "" : principal.toString();
    }
}
