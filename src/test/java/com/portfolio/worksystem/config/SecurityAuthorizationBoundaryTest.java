package com.portfolio.worksystem.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.worksystem.security.LoginAttemptService;
import com.portfolio.worksystem.service.SystemSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@link SecurityConfig} 의 <b>인가 경계</b>를 실제 필터 체인에 태워 확인한다.
 *
 * <p>왜 필요한가 — 이 저장소의 컨트롤러 27개, 엔드포인트 331개에 {@code @PreAuthorize} 가
 * <b>하나도 없다.</b> 인가는 전적으로 이 파일의 URL 매처와 서비스단 수동 검사에 달려 있다.
 * URL 매칭은 문자열이라 컴파일러가 지켜 주지 않는다 — 경로를 오타 내거나 새 관리 화면을
 * 만들고 매처에 추가하는 것을 잊으면 규칙이 조용히 사라지고 {@code anyRequest().authenticated()}
 * 로 흘러내린다. 로그인만 하면 남의 인사 데이터에 닿는다는 뜻이다.
 *
 * <p>매처는 <b>순서가 의미를 가진다</b>. 위에 걸린 규칙이 이긴다. 규칙을 옮기는 리팩터링이
 * 가장 위험한데, 이 테스트가 그 순서까지 같이 고정한다.
 *
 * <p>컨트롤러는 일부러 진짜를 쓰지 않는다. 여기서 보려는 것은 컨트롤러의 동작이 아니라
 * <b>요청이 컨트롤러까지 닿는가</b>이므로, 모든 경로를 받아 200 을 돌려주는 것 하나만 둔다.
 * 200 이면 통과한 것이고, 302(로그인으로)나 403 이면 막힌 것이다.
 *
 * <p><b>주의</b> — {@code @WithMockUser(roles=...)} 는 {@code CustomUserDetails} 를 거치지
 * 않는다. 실제 로그인에서는 SUPER_ADMIN 이 ROLE_SYSTEM_ADMIN 을 자동으로 하나 더 받지만
 * (CustomUserDetails 생성자), 이 테스트에서는 명시한 롤만 붙는다. 그래서 아래
 * "SUPER_ADMIN 이 SYSTEM_ADMIN 경로에 닿는다" 는 별도로 두 롤을 함께 주어 확인한다.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SecurityAuthorizationBoundaryTest.TestApp.class)
class SecurityAuthorizationBoundaryTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // springSecurity() 가 springSecurityFilterChain 을 MockMvc 앞에 끼운다.
        // 이것이 있어야 인가가 <실제로> 돌고, 없으면 전부 통과해 테스트가 무의미해진다.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @EnableWebMvc
    @Configuration
    @Import({SecurityConfig.class, ReleaseFeatures.class})
    static class TestApp {

        /* SecurityConfig 가 필터 체인을 만들 때 주입받는 것들. 인가 경계만 보므로 동작은 필요 없다. */
        @Bean
        SystemSettingsService systemSettingsService() {
            return Mockito.mock(SystemSettingsService.class);
        }

        @Bean
        LoginAttemptService loginAttemptService() {
            return Mockito.mock(LoginAttemptService.class);
        }

        /* [PERM] 관리자 메뉴는 메뉴별 권한으로 지킨다 — 여기서는 V91 이 기존 역할을 같은 메뉴 권한으로 옮긴 상태. */
        @Bean
        com.portfolio.worksystem.service.AdminPermissionService adminPermissionService() {
            return com.portfolio.worksystem.support.AdminPermissionStub.legacyRoles();
        }

        @Bean
        PassThroughController passThroughController() {
            return new PassThroughController();
        }
    }

    /** 인가를 통과한 요청만 여기까지 온다. */
    @RestController
    static class PassThroughController {
        @RequestMapping("/**")
        String any() {
            return "ok";
        }

        @PostMapping("/**")
        String anyPost() {
            return "ok";
        }
    }

    @Test
    @com.portfolio.worksystem.support.WithRoles({"SUPER_ADMIN", "SYSTEM_ADMIN", "HR_ADMIN"})
    void noAdministrativeRoleCanReenableRetiredRoutes() throws Exception {
        for (String path : new String[]{"/eval", "/eval/tasks", "/eval/kpi", "/eval/kpi/result",
                "/admin/eval", "/admin/eval/templates",
                "/hr/performance", "/api/desktop/eval/notice", "/admin/org/kpi-target"}) {
            denied(path);
            mvc.perform(post(path).with(csrf())).andExpect(status().isForbidden());
        }
        allowed("/hr/attendance");
        allowed("/hr/leave/ledger");
        allowed("/report/my");
        allowed("/admin/users");
    }

    private void allowed(String path) throws Exception {
        mvc.perform(get(path).with(csrf())).andExpect(status().isOk());
    }

    private void denied(String path) throws Exception {
        mvc.perform(get(path).with(csrf())).andExpect(status().isForbidden());
    }

    private void redirectedToLogin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().is3xxRedirection());
    }

    @Nested
    @DisplayName("로그인하지 않은 요청")
    class Anonymous {

        /*
          여기 있는 것들이 막히면 로그인 화면 자체가 안 뜨거나(테마·로고), 모니터링이
          서버를 죽은 것으로 보거나, 데스크톱 클라이언트가 로그인조차 못 한다.
        */
        @Test
        @WithAnonymousUser
        void loginScreenAndHealthAndBrandingAreOpen() throws Exception {
            allowed("/login");
            allowed("/system-theme.css");
            allowed("/system-logo/main.png");
            allowed("/api/system/theme");
            allowed("/api/system/branding");
            allowed("/actuator/health");
            allowed("/actuator/info");
        }

        /** OTP 는 비밀번호만 통과한 상태(미인증)에서 들어온다. */
        @Test
        @WithAnonymousUser
        void otpStepIsReachableBeforeAuthentication() throws Exception {
            allowed("/login/otp/verify");
        }

        /** 프로필·서명·공지 이미지는 로그인 화면과 메일 등에서도 쓰여 무인증이다. */
        @Test
        @WithAnonymousUser
        void publicImagePathsAreOpen() throws Exception {
            allowed("/profile-images/1.png");
            allowed("/signature-images/1.png");
            allowed("/notice-images/1.png");
            allowed("/system-logos/1.png");
        }

        /*
          데스크톱은 로그인 전에 이 경로들을 부른다. 배포시스템 알림은 X-SSO-SECRET 으로
          서비스 단에서 검증하므로 인가는 열어 둔다(연동 계약 문서 §2-3 참조).
        */
        @Test
        @WithAnonymousUser
        void desktopBootstrapAndInternalDeployAreOpen() throws Exception {
            allowed("/api/desktop/login");
            allowed("/api/desktop/refresh");
            allowed("/api/desktop/releases/latest");
            allowed("/desktop/sso/some-token");
            allowed("/api/internal/deploy/notifications");
        }

        /*
          나머지는 전부 막혀야 한다. anyRequest().authenticated() 로 흘러내리는지 확인하는
          자리이기도 하다 — 새 경로를 permitAll 목록에 실수로 넣으면 여기서 걸린다.
        */
        @Test
        @WithAnonymousUser
        void everythingElseRedirectsToLogin() throws Exception {
            redirectedToLogin("/dashboard");
            redirectedToLogin("/hr/members/1");
            redirectedToLogin("/eval/cycles");
            redirectedToLogin("/admin/users");
            redirectedToLogin("/api/notifications");
            redirectedToLogin("/mypage");
        }
    }

    @Nested
    @DisplayName("시스템 운영은 SUPER_ADMIN 만")
    class SuperAdminOnly {

        /* 시스템 설정과 릴리스 배포는 이 시스템에서 가장 무거운 권한이다. */
        @Test
        @com.portfolio.worksystem.support.WithRoles("SUPER_ADMIN")
        void superAdminReachesSystemSurface() throws Exception {
            allowed("/admin/system/settings");
            allowed("/admin/releases/upload");
        }

        @Test
        @com.portfolio.worksystem.support.WithRoles("SYSTEM_ADMIN")
        void systemAdminCannotTouchSystemOrReleases() throws Exception {
            denied("/admin/system/settings");
            denied("/admin/releases/upload");
        }

        @Test
        @com.portfolio.worksystem.support.WithRoles({"HR_ADMIN", "ERP_ADMIN", "NOTICE_ADMIN"})
        void otherAdminsCannotTouchSystemOrReleases() throws Exception {
            denied("/admin/system/settings");
            denied("/admin/releases/upload");
        }
    }

    @Nested
    @DisplayName("관리 화면별 롤 경계")
    class AdminSurfaces {

        @Test
        @com.portfolio.worksystem.support.WithRoles("SYSTEM_ADMIN")
        void systemAdminReachesSettingsAndOrgAndUsers() throws Exception {
            // [PERM] 예전 '관리자 지정'(업무 역할)은 슈퍼관리자 전용이 됐다. 메뉴 권한은 '관리자 권한 관리'에서 준다.
            denied("/admin/settings/general");
            allowed("/admin/permissions");
            allowed("/admin/org/tree");
            allowed("/admin/users/1");
            allowed("/admin/approval-routes/1");
            allowed("/admin/hr-diagnostics/run");
        }

        /* 인사 관리자는 조직·사용자·결재선·HR 점검까지. 시스템 설정은 아니다. */
        @Test
        @com.portfolio.worksystem.support.WithRoles("HR_ADMIN")
        void hrAdminReachesOrgAndUsersButNotSettings() throws Exception {
            allowed("/admin/org/tree");
            allowed("/admin/users/1");
            allowed("/admin/hr-diagnostics/run");
            denied("/admin/settings/general");
        }

        /* 인사 관리자는 직급·인사이동까지. 제거된 평가 기능에는 접근할 수 없다. */
        @Test
        @com.portfolio.worksystem.support.WithRoles("HR_ADMIN")
        void hrAdminReachesGradesAndTransferButNotRetiredRoutes() throws Exception {
            allowed("/admin/grades/list");
            allowed("/admin/personnel/transfer");
            denied("/admin/eval/cycles");
        }

        @Test
        @com.portfolio.worksystem.support.WithRoles("NOTICE_ADMIN")
        void noticeAdminReachesOnlyNotices() throws Exception {
            allowed("/admin/notices/1");
            denied("/admin/org/tree");
            denied("/admin/eval/cycles");
            denied("/admin/settings/general");
        }

        /* ERP 관리자는 평가 쪽에 닿지 않는다. */
        @Test
        @com.portfolio.worksystem.support.WithRoles("ERP_ADMIN")
        void erpAdminCannotReachRetiredRoutes() throws Exception {
            allowed("/admin/org/tree");
            denied("/admin/eval/cycles");
            denied("/admin/grades/list");
        }

        /* 일반 직원은 어떤 관리 화면에도 닿지 않는다. */
        @Test
        @com.portfolio.worksystem.support.WithRoles("EMPLOYEE")
        void employeeReachesNoAdminSurface() throws Exception {
            denied("/admin/system/settings");
            denied("/admin/settings/general");
            denied("/admin/org/tree");
            denied("/admin/users/1");
            denied("/admin/eval/cycles");
            denied("/admin/grades/list");
            denied("/admin/notices/1");
            denied("/admin/personnel/transfer");
            denied("/admin/hr-diagnostics/run");
            denied("/admin/approval-routes/1");
            denied("/admin/releases/upload");
        }
    }

    @Nested
    @DisplayName("근태와 직원 정보의 대상 범위는 서비스 계층에서 검사한다")
    class ScopeGovernedSurfaces {

        @Test
        @com.portfolio.worksystem.support.WithRoles("EMPLOYEE")
        void employeeReachesAttendanceButNotRetiredRoutes() throws Exception {
            allowed("/hr/members/1");
            allowed("/hr/attendance");
            denied("/eval/cycles");
        }

        @Test
        @WithAnonymousUser
        void anonymousIsStillBlocked() throws Exception {
            redirectedToLogin("/hr/members/1");
            redirectedToLogin("/eval/cycles");
        }
    }

    /*
      CSRF 토큰 없는 상태 변경은 막혀야 한다. 세션 쿠키만 있으면 되는 요청이 열려 있으면
      사내 다른 페이지에서 근태·결재를 조작할 수 있다.

      데스크톱 API 와 배포 알림은 의도적으로 예외다(각각 디바이스 토큰·공유 시크릿으로
      검증). 세션 쿠키가 SameSite=Lax 라 교차 사이트 POST 에는 쿠키가 실리지 않는다.
    */
    @Nested
    @DisplayName("CSRF")
    class Csrf {

        /*
          [E403] 서버 재시작으로 세션·CSRF 가 무효화된 뒤의 <일반 폼 제출> 은 Whitelabel 403
          대신 로그인 화면으로 돌린다(안내 배너 relogin=1). 사용자가 흰 화면을 보지 않게
          하려는 의도된 동작이라, 403 이 아니라 302 인 것이 정상이다.
        */
        @Test
        @com.portfolio.worksystem.support.WithRoles("SUPER_ADMIN")
        void staleFormSubmitWithoutTokenGoesToLoginInsteadOfWhitelabel403() throws Exception {
            mvc.perform(post("/admin/system/settings"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .redirectedUrl("/login?relogin=1"));
        }

        /*
          반면 API·AJAX 는 403 을 유지해야 한다. 여기가 302 로 바뀌면 fetch 가 로그인 HTML 을
          JSON 으로 파싱하려다 엉뚱한 오류를 내고, 화면의 forceLogout 처리가 동작하지 않는다.
        */
        @Test
        @com.portfolio.worksystem.support.WithRoles("SUPER_ADMIN")
        void apiRequestWithoutTokenStaysA403() throws Exception {
            mvc.perform(post("/api/notifications/read-all")).andExpect(status().isForbidden());
        }

        @Test
        @com.portfolio.worksystem.support.WithRoles("SUPER_ADMIN")
        void stateChangingRequestWithTokenPasses() throws Exception {
            mvc.perform(post("/admin/system/settings").with(csrf())).andExpect(status().isOk());
        }

        @Test
        @com.portfolio.worksystem.support.WithRoles("EMPLOYEE")
        void desktopAndInternalDeployApisAreCsrfExemptByDesign() throws Exception {
            mvc.perform(post("/api/desktop/attendance/check-in")).andExpect(status().isOk());
            mvc.perform(post("/api/internal/deploy/notifications")).andExpect(status().isOk());
        }
    }
}
