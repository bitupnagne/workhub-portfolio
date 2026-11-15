package com.portfolio.worksystem.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MustChangePasswordGuardConfigTest {

    @Test
    void allowsOnlyTheExactSessionHeartbeatWhilePasswordChangeIsRequired() {
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/session/ping")).isTrue();

        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/session/ping/extra")).isFalse();
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/right-panel")).isFalse();
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/work-memos/unread-count")).isFalse();
    }

    @Test
    void preservesTheExistingPasswordChangeAndPageAllowances() {
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/mypage")).isTrue();
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/mypage/password")).isTrue();
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/mypage/password/verify")).isTrue();
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/js/worksystem-password-modal.js")).isTrue();
    }

    @Test
    void permitsDesktopAuthenticationStepsWithoutOpeningProtectedDataOrSso() {
        for (String path : new String[]{"login", "refresh", "logout", "password",
                "otp/state", "otp/setup", "otp/enroll", "otp/verify"}) {
            assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/desktop/" + path)).isTrue();
            assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/desktop/" + path + "/extra")).isFalse();
        }
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/desktop/sso-token")).isFalse();
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/desktop/me")).isFalse();
        assertThat(MustChangePasswordGuardConfig.isAllowedPath("/api/desktop/attendance/check-in")).isFalse();
    }
}
