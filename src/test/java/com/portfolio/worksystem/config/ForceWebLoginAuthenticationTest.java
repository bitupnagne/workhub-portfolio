package com.portfolio.worksystem.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.security.LoginAttemptService;
import com.portfolio.worksystem.service.SystemSettingsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ForceWebLoginAuthenticationTest.TestApp.class)
@WebAppConfiguration
class ForceWebLoginAuthenticationTest {
    private static final String USER = "synthetic-session-user";
    private static final String PASSWORD = "synthetic-unit-test-password";
    @Autowired WebApplicationContext context;
    @Autowired SessionRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        for (Object principal : registry.getAllPrincipals()) {
            registry.getAllSessions(principal, true).forEach(session -> registry.removeSessionInformation(session.getSessionId()));
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void wrongPasswordWithForceDoesNotExpireTheAuthenticatedBrowser() throws Exception {
        MockHttpSession first = login(USER, PASSWORD, false);
        mvc.perform(loginRequest(USER, "incorrect-password", true))
                .andExpect(redirectedUrl("/login?error=true")).andExpect(unauthenticated());
        assertFalse(registry.getSessionInformation(first.getId()).isExpired());
        mvc.perform(get("/org").session(first)).andExpect(status().isOk()).andExpect(authenticated().withUsername(USER));
    }

    @Test
    void correctPasswordWithoutForceRejectsSecondBrowserAndPreservesFirst() throws Exception {
        MockHttpSession first = login(USER, PASSWORD, false);
        mvc.perform(loginRequest(USER, PASSWORD, false))
                .andExpect(redirectedUrl("/login?conflict=true&username=" + USER)).andExpect(unauthenticated());
        assertFalse(registry.getSessionInformation(first.getId()).isExpired());
        mvc.perform(get("/org").session(first)).andExpect(status().isOk()).andExpect(authenticated());
    }

    @Test
    void verifiedForceExpiresOnlyTheAuthenticatedAccountBeforeConcurrencyCheck() throws Exception {
        MockHttpSession first = login(USER, PASSWORD, false);
        // The provider returns the canonical account name; session expiry must use it.
        MockHttpServletRequestBuilder request = loginRequest(USER.toUpperCase(java.util.Locale.ROOT), PASSWORD, true);
        MockHttpSession preLogin = new MockHttpSession();
        String beforeFixation = preLogin.getId();
        MvcResult result = mvc.perform(request.session(preLogin)).andExpect(redirectedUrl("/org"))
                .andExpect(authenticated().withUsername(USER)).andReturn();
        MockHttpSession second = (MockHttpSession) result.getRequest().getSession(false);
        assertNotEquals(beforeFixation, second.getId(), "Default session fixation protection must remain active");
        assertTrue(registry.getSessionInformation(first.getId()).isExpired());
        mvc.perform(get("/org").session(first)).andExpect(redirectedUrl("/login?expired=true")).andExpect(unauthenticated());
        mvc.perform(get("/org").session(second)).andExpect(status().isOk()).andExpect(authenticated());
    }

    private MockHttpSession login(String username, String password, boolean force) throws Exception {
        MvcResult result = mvc.perform(loginRequest(username, password, force))
                .andExpect(redirectedUrl("/org")).andExpect(authenticated()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder loginRequest(String username, String password, boolean force) {
        return post("/login").servletPath("/login").with(csrf())
                .param("username", username).param("password", password).param("force", Boolean.toString(force));
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, ReleaseFeatures.class})
    static class TestApp {
        @Bean SystemSettingsService systemSettingsService() { return Mockito.mock(SystemSettingsService.class); }
        @Bean LoginAttemptService loginAttemptService() { return Mockito.mock(LoginAttemptService.class); }
        @Bean PassThroughController passThroughController() { return new PassThroughController(); }
        @Bean UserDetailsService userDetailsService(PasswordEncoder encoder) {
            String encoded = encoder.encode(PASSWORD);
            return username -> {
                if (!USER.equalsIgnoreCase(username)) throw new UsernameNotFoundException("Synthetic account not found");
                UserAccount account = new UserAccount();
                account.setId(990001L);
                account.setUsername(USER);
                account.setPassword(encoded);
                account.setActive(true);
                account.setRoles(List.of("EMPLOYEE"));
                return new CustomUserDetails(account);
            };
        }
    }

    @RestController
    static class PassThroughController {
        @GetMapping({"/org", "/dashboard", "/login"}) String page() { return "synthetic test page"; }
    }
}
