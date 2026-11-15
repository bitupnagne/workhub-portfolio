package com.portfolio.worksystem.service;

import com.portfolio.worksystem.controller.MyPageController;
import com.portfolio.worksystem.controller.OtpController;
import com.portfolio.worksystem.mapper.DesktopAuthMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.DesktopDeviceToken;
import com.portfolio.worksystem.model.DesktopSsoToken;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.security.LoginAttemptService;
import com.portfolio.worksystem.websocket.RealtimeHub;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopAuthOtpEvidenceTest {
    private DesktopAuthMapper mapper;
    private UserMapper users;
    private PasswordEncoder passwords;
    private DesktopAuthService service;
    private UserAccount account;
    private CustomUserDetails user;
    private final Map<String, DesktopSsoToken> tokens = new HashMap<>();

    @BeforeEach
    void setUp() {
        mapper = mock(DesktopAuthMapper.class);
        users = mock(UserMapper.class);
        passwords = mock(PasswordEncoder.class);
        service = new DesktopAuthService(mapper, users, passwords, mock(RealtimeHub.class),
                mock(LoginAttemptService.class), mock(SystemSettingsService.class));
        account = new UserAccount();
        account.setId(42L);
        account.setUsername("desktop-user");
        account.setPassword("encoded");
        account.setName("사용자");
        account.setActive(true);
        account.setOtpEnabled(true);
        account.setOtpSecret("secret");
        user = new CustomUserDetails(account);
        when(users.findById(42L)).thenReturn(account);
        when(users.findByUsername("desktop-user")).thenReturn(account);
        when(users.findRolesByUserId(42L)).thenReturn(List.of("USER"));
        when(passwords.matches("password", "encoded")).thenReturn(true);
        doAnswer(call -> {
            DesktopSsoToken token = call.getArgument(0);
            token.setId((long) tokens.size() + 1);
            tokens.put(token.getTokenHash(), token);
            return null;
        }).when(mapper).insertSsoToken(any());
        when(mapper.findUsableSsoToken(anyString(), any())).thenAnswer(call -> {
            DesktopSsoToken token = tokens.get(call.getArgument(0));
            LocalDateTime now = call.getArgument(1);
            return token != null && token.getUsedAt() == null && token.getExpiresAt().isAfter(now) ? token : null;
        });
        when(mapper.markSsoTokenUsed(anyLong(), any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            LocalDateTime now = call.getArgument(1);
            DesktopSsoToken token = tokens.values().stream().filter(t -> id.equals(t.getId())).findFirst().orElseThrow();
            if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) return 0;
            token.setUsedAt(now);
            return 1;
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ssoCarriesOnlyVerifiedEvidenceAndMyPageGateUsesIt(boolean verified) {
        MockHttpServletRequest desktop = new MockHttpServletRequest();
        if (verified) desktop.getSession().setAttribute(OtpController.OTP_VERIFIED, true);
        String rawToken = service.createSsoToken(user, "/deploy-system/sso", desktop);

        MockHttpServletRequest browser = new MockHttpServletRequest();
        // 브라우저에서 전에 로그인한 다른 사용자의 증거가 남아 있어도 새 토큰으로 대체해야 한다.
        browser.getSession().setAttribute(OtpController.OTP_VERIFIED, true);
        browser.getSession().setAttribute(OtpController.OTP_SETUP_SECRET, "other-user-secret");
        assertThat(service.consumeSso(rawToken, browser)).isEqualTo("/deploy-system/sso");
        assertThat(browser.getSession().getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(verified ? true : null);
        assertThat(browser.getSession().getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
        var context = (org.springframework.security.core.context.SecurityContext) browser.getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        CustomUserDetails browserUser = (CustomUserDetails) context.getAuthentication().getPrincipal();
        DeploySsoService deploy = mock(DeploySsoService.class);
        MyPageController myPage = new MyPageController(new com.portfolio.worksystem.config.ReleaseFeatures("full"), mock(AccountService.class), mock(OrganizationService.class),
                deploy, mock(EmpGradeService.class), mock(HrService.class));
        when(deploy.issueLoginUrl(browserUser, null)).thenReturn("https://deploy.example/sso/consume");
        String result = myPage.openDeploySystem(browserUser, null, browser.getSession(), new RedirectAttributesModelMap());
        assertThat(result).isEqualTo(verified ? "redirect:https://deploy.example/sso/consume" : "redirect:/mypage");
        if (verified) verify(deploy).issueLoginUrl(browserUser, null);
        else verify(deploy, never()).issueLoginUrl(any(), any());
        assertThatThrownBy(() -> service.consumeSso(rawToken, new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void absentSessionAndPendingStateCannotSupplyOtpEvidence() {
        MockHttpServletRequest noSession = new MockHttpServletRequest();
        service.createSsoToken(user, "/dashboard", noSession);
        assertThat(noSession.getSession(false)).isNull();
        MockHttpServletRequest pending = new MockHttpServletRequest();
        pending.getSession().setAttribute(OtpController.OTP_VERIFIED, true);
        pending.getSession().setAttribute(DesktopAuthService.OTP_PENDING_SESSION, true);
        service.createSsoToken(user, "/dashboard", pending);
        assertThat(tokens.values()).allMatch(token -> !token.isOtpVerified());
    }

    @Test
    void expiredVerifiedTokenDoesNotEstablishSession() {
        MockHttpServletRequest desktop = new MockHttpServletRequest();
        desktop.getSession().setAttribute(OtpController.OTP_VERIFIED, true);
        String rawToken = service.createSsoToken(user, "/dashboard", desktop);
        tokens.values().iterator().next().setExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(1));
        MockHttpServletRequest browser = new MockHttpServletRequest();
        assertThatThrownBy(() -> service.consumeSso(rawToken, browser)).isInstanceOf(ResponseStatusException.class);
        assertThat(browser.getSession(false)).isNull();
        verify(mapper, never()).markSsoTokenUsed(anyLong(), any());
    }

    @Test
    void tokenClaimLostToAnotherRequestCannotEstablishSession() {
        String rawToken = service.createSsoToken(user, "/dashboard", new MockHttpServletRequest());
        doReturn(0).when(mapper).markSsoTokenUsed(eq(1L), any());
        MockHttpServletRequest browser = new MockHttpServletRequest();
        assertThatThrownBy(() -> service.consumeSso(rawToken, browser)).isInstanceOf(ResponseStatusException.class);
        assertThat(browser.getSession(false)).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void freshLoginAndRefreshClearPreviousUserEvidenceAndSetupSecret(boolean refresh) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(OtpController.OTP_VERIFIED, true);
        request.getSession().setAttribute(OtpController.OTP_SETUP_SECRET, "previous-user-secret");
        request.getSession().setAttribute(OtpController.OTP_PENDING_UID, 99L);
        if (refresh) {
            DesktopDeviceToken deviceToken = new DesktopDeviceToken();
            deviceToken.setId(1L);
            deviceToken.setUserId(42L);
            when(mapper.findActiveDeviceToken(anyString(), any())).thenReturn(deviceToken);
            service.refresh("device-token", request);
        } else {
            service.login("desktop-user", "password", "test-pc", false, request);
        }
        assertThat(request.getSession().getAttribute(OtpController.OTP_VERIFIED)).isNull();
        assertThat(request.getSession().getAttribute(OtpController.OTP_SETUP_SECRET)).isNull();
        assertThat(request.getSession().getAttribute(OtpController.OTP_PENDING_UID)).isNull();
        assertThat(request.getSession().getAttribute(DesktopAuthService.OTP_PENDING_SESSION)).isEqualTo(true);
        // 검증이 필요한 세션의 등록 상태만으로 새 SSO 토큰에 증거를 만들어서는 안 된다.
        service.createSsoToken(user, "/dashboard", request);
        assertThat(tokens.values()).allMatch(token -> !token.isOtpVerified());
    }
}
