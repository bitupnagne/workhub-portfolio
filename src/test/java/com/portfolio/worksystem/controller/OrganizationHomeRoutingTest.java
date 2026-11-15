package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.portfolio.worksystem.config.ReleaseFeatures;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.security.CustomUserDetailsService;
import com.portfolio.worksystem.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class OrganizationHomeRoutingTest {
    @Test void rootAndAlreadyAuthenticatedLoginUseOrganizationForEveryFeatureEdition() {
        var account = new UserAccount(); account.setId(1L); account.setUsername("home-test"); account.setRoles(List.of("EMPLOYEE"));
        var principal = new CustomUserDetails(account);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        for (String edition : List.of("full", "0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8")) {
            var controller = new AuthController(new ReleaseFeatures(edition), mock(ReportService.class));
            assertThat(controller.home(auth)).isEqualTo("redirect:/org");
            assertThat(controller.login(auth, new org.springframework.ui.ExtendedModelMap())).isEqualTo("redirect:/org");
            assertThat(controller.home(null)).isEqualTo("redirect:/login");
        }
    }

    @Test void completedOtpUsesTheSameHomeWithoutChangingPendingAuthenticationBoundary() {
        var totp = mock(TotpService.class); var details = mock(CustomUserDetailsService.class);
        var account = new UserAccount(); account.setId(1L); account.setUsername("otp-test"); account.setRoles(List.of("EMPLOYEE"));
        account.setOtpEnabled(true); account.setOtpSecret("test-secret"); var principal = new CustomUserDetails(account);
        when(details.loadUserByUsername("otp-test")).thenReturn(principal); when(totp.verify("test-secret", "123456")).thenReturn(true);
        var controller = new OtpLoginController(new ReleaseFeatures("0.1"), totp, mock(QrCodeService.class), mock(UserMapper.class), details);
        var request = new MockHttpServletRequest(); var session = new MockHttpSession(); request.setSession(session);
        session.setAttribute(OtpController.OTP_PENDING_UID, 1L); session.setAttribute(OtpController.OTP_PENDING_NAME, "otp-test");
        session.setAttribute(OtpController.OTP_PENDING_ENROLLED, true);
        try {
            var response = controller.verify("123456", session, request, new MockHttpServletResponse());
            assertThat(response).containsEntry("ok", true).containsEntry("redirect", "/org");
            assertThat(session.getAttribute(OtpController.OTP_PENDING_UID)).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void desktopDefaultFallbackChangesHomeWhileExplicitLocalTargetsRemainUnchanged() {
        var service = mock(DesktopAuthService.class, CALLS_REAL_METHODS);
        for (String invalid : new String[]{null, "", "https://outside.example/path", "//outside.example/path", "/" + "x".repeat(300)}) {
            assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeRedirect", invalid)).isEqualTo("/org");
        }
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "normalizeRedirect", "/hr/attendance?tab=leave"))
                .isEqualTo("/hr/attendance?tab=leave");
    }
}
