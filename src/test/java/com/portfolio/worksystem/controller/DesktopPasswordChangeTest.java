package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.PasswordChangeForm;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.*;
import com.portfolio.worksystem.mapper.UserMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DesktopPasswordChangeTest {
    private AccountService accounts;
    private DesktopApiController controller;
    private CustomUserDetails user;
    private final PasswordChangeForm form = new PasswordChangeForm("old-password", "NewPassword2026!", "NewPassword2026!");

    @BeforeEach
    void setUp() {
        accounts = mock(AccountService.class);
        controller = new DesktopApiController(mock(DesktopAuthService.class), mock(HrService.class),
                mock(ApprovalService.class), mock(DeploySsoService.class), mock(TotpService.class),
                mock(QrCodeService.class), mock(UserMapper.class), mock(SystemSettingsService.class),
                accounts);
        UserAccount account = new UserAccount();
        account.setId(42L);
        account.setUsername("initial-password-user");
        account.setRoles(List.of("EMPLOYEE"));
        account.setMustChangePassword(true);
        user = new CustomUserDetails(account);
    }

    @Test
    void completesRequiredChangeAndUnlocksTheCurrentPrincipalOnlyAfterPersistenceSucceeds() {
        var response = controller.changePassword(user, form);
        verify(accounts).changePassword(user, form);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("ok", true);
        assertThat(user.isMustChangePassword()).isFalse();
    }

    @Test
    void rejectedPasswordKeepsTheSessionRestrictedAndReturnsAnActionableError() {
        doThrow(new BusinessException("현재 비밀번호가 올바르지 않습니다.")).when(accounts).changePassword(user, form);
        var response = controller.changePassword(user, form);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("ok", false).containsEntry("message", "현재 비밀번호가 올바르지 않습니다.");
        assertThat(user.isMustChangePassword()).isTrue();
    }

    @Test
    void desktopLogoutInvalidatesThePendingAuthenticationSessionWithoutRequiringWebCsrf() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var session = (org.springframework.mock.web.MockHttpSession) request.getSession();
        session.setAttribute(DesktopAuthService.OTP_PENDING_SESSION, true);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, "", user.getAuthorities()));
        controller.logout(null, request);
        assertThat(session.isInvalid()).isTrue();
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
