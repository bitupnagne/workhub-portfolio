package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.DesktopSsoRequest;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.*;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DesktopOtpEvidenceTest {
    private TotpService totp;
    private UserMapper users;
    private DesktopAuthService auth;
    private DesktopApiController controller;
    private CustomUserDetails user;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        totp = mock(TotpService.class);
        users = mock(UserMapper.class);
        auth = mock(DesktopAuthService.class);
        controller = new DesktopApiController(auth, mock(HrService.class), mock(ApprovalService.class),
                mock(DeploySsoService.class), totp, mock(QrCodeService.class), users,
                mock(SystemSettingsService.class), mock(AccountService.class),
                mock(com.portfolio.worksystem.service.PresenceStatusService.class),
                mock(com.portfolio.worksystem.service.OvertimeNoticeService.class),
                mock(com.portfolio.worksystem.service.AttendanceCheckService.class));
        user = mock(CustomUserDetails.class);
        when(user.getUserId()).thenReturn(42L);
        request = new MockHttpServletRequest();
        request.getSession().setAttribute(DesktopAuthService.OTP_PENDING_SESSION, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void verifyRecordsEvidenceOnlyForSuccessfulCode(boolean valid) {
        when(user.isOtpEnabled()).thenReturn(true);
        when(user.getOtpSecret()).thenReturn("secret");
        when(totp.verify("secret", "123456")).thenReturn(valid);
        assertThat(controller.otpVerify(user, Map.of("code", "123456"), request)).containsEntry("ok", valid);
        assertThat(request.getSession().getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(valid ? true : null);
        assertThat(request.getSession().getAttribute(DesktopAuthService.OTP_PENDING_SESSION)).isEqualTo(valid ? null : true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enrollmentRecordsEvidenceOnlyAfterSuccessfulVerification(boolean valid) {
        request.getSession().setAttribute(OtpController.OTP_SETUP_SECRET, "new-secret");
        when(totp.verify("new-secret", "123456")).thenReturn(valid);
        assertThat(controller.otpEnroll(user, Map.of("code", "123456"), request)).containsEntry("ok", valid);
        assertThat(request.getSession().getAttribute(OtpController.OTP_VERIFIED)).isEqualTo(valid ? true : null);
        assertThat(request.getSession().getAttribute(DesktopAuthService.OTP_PENDING_SESSION)).isEqualTo(valid ? null : true);
        assertThat(request.getSession().getAttribute(OtpController.OTP_SETUP_SECRET)).isEqualTo(valid ? null : "new-secret");
        if (valid) verify(users).updateOtp(42L, "new-secret", true);
        else verifyNoInteractions(users);
    }

    @Test
    void unregisteredUserAndMissingSetupSecretCannotProduceEvidence() {
        when(user.isOtpEnabled()).thenReturn(false);
        assertThat(controller.otpVerify(user, Map.of("code", "123456"), request)).containsEntry("ok", false);
        assertThat(controller.otpEnroll(user, Map.of("code", "123456"), request)).containsEntry("ok", false);
        assertThat(request.getSession().getAttribute(OtpController.OTP_VERIFIED)).isNull();
        verifyNoInteractions(totp, users);
    }

    @Test
    void ssoEndpointPassesCurrentRequestWithoutChangingJsonContract() {
        when(auth.createSsoToken(user, "/deploy-system/sso", request)).thenReturn("opaque-token");
        when(auth.ssoExpiresAt()).thenReturn(LocalDateTime.of(2026, 10, 2, 12, 0));
        var response = controller.ssoToken(user, new DesktopSsoRequest("/deploy-system/sso"), request);
        assertThat(response.url()).isEqualTo("/desktop/sso/opaque-token");
        verify(auth).createSsoToken(user, "/deploy-system/sso", request);
    }
}
