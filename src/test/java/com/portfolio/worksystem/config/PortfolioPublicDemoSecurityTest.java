package com.portfolio.worksystem.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.SystemSettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = PortfolioPublicDemoSecurityTest.App.class)
@TestPropertySource(properties = {"app.portfolio.public-demo=true", "server.servlet.session.cookie.name=WORKHUBDEMOSESSION"})
class PortfolioPublicDemoSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired SystemSettingsService settings;
    @Autowired SessionRegistry sessions;
    MockMvc mvc;

    @Configuration
    @Import(SecurityAuthorizationBoundaryTest.TestApp.class)
    static class App {
        @Bean UserDetailsService demoUsers(PasswordEncoder encoder) {
            String password = encoder.encode("demo-test-password");
            return username -> {
                UserAccount account = new UserAccount();
                account.setId(99L);
                account.setUsername(username);
                account.setPassword(password);
                account.setActive(true);
                account.setRoles(List.of("SUPER_ADMIN"));
                return new CustomUserDetails(account);
            };
        }
    }

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test @WithMockUser(roles = {"SUPER_ADMIN", "SYSTEM_ADMIN"})
    void sharedAccountsCannotChangeCredentialsOrReachExternalIntegrations() throws Exception {
        for (String path : List.of("/admin/system/deploy-integration", "/admin/system/otp-policy",
                "/admin/users/102/password-reset", "/mypage/password", "/otp/confirm", "/admin/releases")) {
            mvc.perform(post(path).with(csrf())).andExpect(redirectedUrl("/demo-restricted"));
        }
        mvc.perform(post("/api/internal/deploy/notifications").with(csrf()))
                .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.containsString("공개 데모")));
        mvc.perform(get("/api/desktop/otp/setup"))
                .andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = {"SUPER_ADMIN", "SYSTEM_ADMIN"})
    void projectAndReportEditsAndSettingsPreviewRemainAvailable() throws Exception {
        mvc.perform(post("/projects").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/projects/1/tasks/2/handover").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/report/save").with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/admin/system")).andExpect(status().isOk());
    }

    @Test void visitorsCanLogInTogetherWithoutOtpOrForcingOthersOut() throws Exception {
        when(settings.otpRequireAdmin()).thenReturn(true);
        when(settings.otpRequireExternal()).thenReturn(true);
        var first = mvc.perform(post("/login").with(csrf()).param("username", "demo.admin")
                        .param("password", "demo-test-password"))
                .andExpect(redirectedUrl("/org")).andReturn();
        var firstSession = (MockHttpSession) first.getRequest().getSession(false);
        mvc.perform(post("/login").with(csrf()).param("username", "demo.admin")
                        .param("password", "demo-test-password").param("force", "true"))
                .andExpect(redirectedUrl("/org"));
        assertThat(sessions.getSessionInformation(firstSession.getId()).isExpired()).isFalse();
        mvc.perform(get("/projects").session(firstSession)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(firstSession).with(csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie().maxAge("WORKHUBDEMOSESSION", 0));
    }
}
