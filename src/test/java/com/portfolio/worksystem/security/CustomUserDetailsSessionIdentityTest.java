package com.portfolio.worksystem.security;

import static org.junit.jupiter.api.Assertions.*;

import com.portfolio.worksystem.model.UserAccount;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;

class CustomUserDetailsSessionIdentityTest {
    @Test
    void freshPrincipalForSameLoginFindsTheExistingBrowserSession() {
        CustomUserDetails first = new CustomUserDetails(account(17L, "session-test"));
        CustomUserDetails second = new CustomUserDetails(account(17L, "session-test"), false);
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("first-browser", first);

        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(List.of("first-browser"), registry.getAllSessions(second, false).stream()
                .map(session -> session.getSessionId()).toList());
    }

    @Test
    void profileAndSecurityUpdatesDoNotMoveTheRegisteredIdentity() {
        UserAccount backing = account(17L, "session-test");
        CustomUserDetails principal = new CustomUserDetails(backing);
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("existing-browser", principal);
        int originalHash = principal.hashCode();

        principal.updateBasicInfo("Changed department", "100", "01000000000", "Changed title", "qa@example.invalid");
        principal.updateProfileImageFilename("qa-image.png");
        principal.markPasswordChanged();
        principal.applyOtpState("synthetic-unit-test-value", true);
        UserAccount display = account(17L, "session-test");
        display.setName("Changed display name");
        display.setTeamId(44L);
        principal.syncDisplay(display);
        backing.setUsername("unrelated-later-mutation");

        assertEquals("session-test", principal.getUsername());
        assertEquals(originalHash, principal.hashCode());
        assertEquals(1, registry.getAllSessions(new CustomUserDetails(account(17L, "session-test")), false).size());
        registry.removeSessionInformation("existing-browser");
        assertTrue(registry.getAllSessions(principal, false).isEmpty());
    }

    @Test
    void differentLoginNamesRemainSeparateAndUnnamedObjectsDoNotShareIdentity() {
        CustomUserDetails first = new CustomUserDetails(account(17L, "first-login"));
        CustomUserDetails second = new CustomUserDetails(account(17L, "other-login"));
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("first-browser", first);

        assertNotEquals(first, second);
        assertNotEquals(first, null);
        assertNotEquals(first, "first-login");
        assertTrue(registry.getAllSessions(second, false).isEmpty());
        CustomUserDetails unnamed = new CustomUserDetails(account(18L, null));
        assertEquals(unnamed, unnamed);
        assertNotEquals(unnamed, new CustomUserDetails(account(18L, null)));
    }

    @Test
    void realConcurrentSessionStrategyRejectsSecondBrowserAndAllowsLoginAfterLogout() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("first-browser", new CustomUserDetails(account(17L, "session-test")));
        ConcurrentSessionControlAuthenticationStrategy strategy = new ConcurrentSessionControlAuthenticationStrategy(registry);
        strategy.setMaximumSessions(1);
        strategy.setExceptionIfMaximumExceeded(true);
        CustomUserDetails fresh = new CustomUserDetails(account(17L, "session-test"));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities());
        MockHttpServletRequest secondBrowser = new MockHttpServletRequest();
        secondBrowser.setSession(new MockHttpSession(null, "second-browser"));

        assertThrows(SessionAuthenticationException.class,
                () -> strategy.onAuthentication(authentication, secondBrowser, new MockHttpServletResponse()));
        assertFalse(registry.getSessionInformation("first-browser").isExpired());
        MockHttpServletRequest existingBrowser = new MockHttpServletRequest();
        existingBrowser.setSession(new MockHttpSession(null, "first-browser"));
        assertDoesNotThrow(() -> strategy.onAuthentication(authentication, existingBrowser, new MockHttpServletResponse()));
        registry.removeSessionInformation("first-browser");
        assertDoesNotThrow(() -> strategy.onAuthentication(authentication, secondBrowser, new MockHttpServletResponse()));
    }

    private static UserAccount account(Long id, String username) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setUsername(username);
        account.setName("Synthetic QA user");
        account.setActive(true);
        account.setRoles(List.of("EMPLOYEE"));
        return account;
    }
}
