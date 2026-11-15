package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.AccountService;
import com.portfolio.worksystem.service.DeploySsoService;
import com.portfolio.worksystem.service.EmpGradeService;
import com.portfolio.worksystem.service.HrService;
import com.portfolio.worksystem.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyPageDeploySsoOtpTest {
    private DeploySsoService deploySsoService;
    private MyPageController controller;
    private CustomUserDetails user;
    private MockHttpSession session;
    private RedirectAttributesModelMap redirectAttributes;

    @BeforeEach
    void setUp() {
        deploySsoService = mock(DeploySsoService.class);
        controller = new MyPageController(new com.portfolio.worksystem.config.ReleaseFeatures("full"), mock(AccountService.class), mock(OrganizationService.class),
                deploySsoService, mock(EmpGradeService.class), mock(HrService.class));
        user = mock(CustomUserDetails.class);
        session = new MockHttpSession();
        redirectAttributes = new RedirectAttributesModelMap();
    }

    @Test
    void passwordOnlySessionCannotIssueDeploySsoTicket() {
        when(user.isOtpEnabled()).thenReturn(false);

        assertThat(controller.moveDeploySystem(user, null, session, redirectAttributes)).isEqualTo("redirect:/mypage");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("error");
        verifyNoInteractions(deploySsoService);
    }

    @Test
    void enrolledUserWithoutVerifiedSessionCannotUseGetRoute() {
        when(user.isOtpEnabled()).thenReturn(true);

        assertThat(controller.openDeploySystem(user, null, session, redirectAttributes)).isEqualTo("redirect:/mypage");
        verifyNoInteractions(deploySsoService);
    }

    @Test
    void verifiedOtpSessionCanIssueDeploySsoTicket() {
        when(user.isOtpEnabled()).thenReturn(true);
        session.setAttribute(OtpController.OTP_VERIFIED, Boolean.TRUE);
        when(deploySsoService.issueLoginUrl(user, "/deploy/dashboard"))
                .thenReturn("http://deploy.local/sso/consume?ticket=opaque");

        assertThat(controller.moveDeploySystem(user, "/deploy/dashboard", session, redirectAttributes))
                .isEqualTo("redirect:http://deploy.local/sso/consume?ticket=opaque");
        verify(deploySsoService).issueLoginUrl(user, "/deploy/dashboard");
    }
}
