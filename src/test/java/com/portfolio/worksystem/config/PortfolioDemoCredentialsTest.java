package com.portfolio.worksystem.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class PortfolioDemoCredentialsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserMapper users = mock(UserMapper.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final PortfolioDemoBootstrap bootstrap = new PortfolioDemoBootstrap(jdbc, users, projects, passwords);

    @Test void disabledModeDoesNotReadOrWriteTheDatabase() {
        bootstrap.run(new DefaultApplicationArguments());
        verifyNoInteractions(jdbc, users, projects, passwords);
    }

    @Test void dedicatedDatabaseIsRequiredBeforeAnyActivation() {
        ReflectionTestUtils.setField(bootstrap, "publicDemo", true);
        when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn("unrelated");
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        verify(jdbc).queryForObject("SELECT DATABASE()", String.class);
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(users, projects, passwords);
    }

    @Test void missingCredentialCannotActivateSeedIdentities() {
        ReflectionTestUtils.setField(bootstrap, "publicDemo", true);
        when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn("workhub_demo");
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        verify(jdbc).queryForObject("SELECT DATABASE()", String.class);
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(users, projects, passwords);
    }

    @Test void disabledSeedValueIsNeverAnAuthenticatableHash() {
        assertThat(new BCryptPasswordEncoder().matches(java.util.UUID.randomUUID().toString(), "!DEMO_LOGIN_DISABLED")).isFalse();
    }
}
