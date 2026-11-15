package com.portfolio.worksystem.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.worksystem.model.Notification;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReleaseFeaturesTest {
    @ParameterizedTest
    @CsvSource({"0.1,1", "0.2,2", "0.3,3", "0.4,4", "0.5,5", "0.6,6", "0.7,7", "0.8,8", "full,8"})
    void cumulativeEditionsEnforceEveryEntryPoint(String edition, int stage) throws Exception {
        ReleaseFeatures features = new ReleaseFeatures(edition);
        assertThat(features.isPresenceEnabled()).as("presence %s", edition).isEqualTo(stage >= 5);
        String[][] routes = {
                {"2", "/api/hr/approval-route/warning", "/my-work", "/hr/attendance", "/hr/leave/new", "/hr/trips/new", "/hr/documents", "/hr/loa", "/api/notifications/read-all", "/admin/approval-routes", "/admin/hr-diagnostics", "/mypage/signature", "/admin/users/1/signature", "/admin/users/update"},
                {"3", "/projects", "/projects/4/tasks/7", "/projects/4/files/8", "/report/my", "/report/item", "/admin/org/weekly-target"},
                {"4", "/notices/1", "/notifications", "/admin/notices", "/notice-images/image.png"},
                {"5", "/dashboard"},
                {"6", "/api/messages/1", "/api/work-memos/unread-count", "/work-memos"},
                {"7", "/api/internal/deploy/notifications", "/deploy-system/sso", "/mypage/deploy-sso/link", "/admin/system/deploy-integration"},
                {"8", "/api/desktop/login", "/api/desktop/releases/latest/download", "/desktop/sso/token", "/admin/releases"}
        };
        for (String[] group : routes) {
            for (int index = 1; index < group.length; index++) {
                String path = group[index];
                boolean allowed = stage >= Integer.parseInt(group[0]);
                assertThat(features.allowsPath(path)).as("%s %s", edition, path).isEqualTo(allowed);
                for (String method : new String[]{"GET", "POST"}) {
                    AtomicBoolean invoked = new AtomicBoolean();
                    var response = new MockHttpServletResponse();
                    new ReleaseFeatureFilter(features).doFilter(new MockHttpServletRequest(method, path), response,
                            (request, result) -> invoked.set(true));
                    assertThat(invoked.get()).as("%s %s %s", edition, method, path).isEqualTo(allowed);
                    assertThat(response.getStatus()).isEqualTo(allowed ? 200 : 404);
                }
            }
        }
        for (String path : new String[]{"/people", "/people/1", "/org", "/admin/users", "/api/right-panel", "/ws/live"}) {
            assertThat(features.allowsPath(path)).as("core %s %s", edition, path).isTrue();
        }
        assertThat(features.getHomeUrl()).isEqualTo("/org");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0.1", "0.4", "0.8", "full"})
    void retiredFeaturesReturnNotFoundBeforeReachingApplicationCode(String edition) throws Exception {
        ReleaseFeatures features = new ReleaseFeatures(edition);
        for (String path : new String[]{"/eval/tasks", "/eval/kpi", "/admin/eval", "/admin/grades",
                "/admin/personnel/transfer", "/hr/performance", "/api/desktop/eval/notice",
                "/admin/org/kpi-target", "/%65val/tasks", "/api/desktop/%65val/notice"}) {
            for (String method : new String[]{"GET", "POST"}) {
                AtomicBoolean invoked = new AtomicBoolean();
                var response = new MockHttpServletResponse();
                new ReleaseFeatureFilter(features).doFilter(new MockHttpServletRequest(method, path), response,
                        (request, result) -> invoked.set(true));
                assertThat(invoked.get()).as("%s %s %s", edition, method, path).isFalse();
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
        assertThat(features.allowsNotification(notification("EVAL_KPI", null))).isFalse();
    }

    @Test
    void earlyNotificationPanelOnlyContainsAttendanceWorkflow() {
        assertThat(new ReleaseFeatures("0.1").allowsNotification(notification("APPROVAL_REQUEST", "/hr/leave/1/document"))).isFalse();
        ReleaseFeatures early = new ReleaseFeatures("0.2");
        for (String link : new String[]{"/hr/leave/1/document", "/hr/loa/1/document", "/hr/trips/1/document", "/hr/flex-time/1/document", "/hr/attendance/correction/1/document"}) {
            assertThat(early.allowsNotification(notification("APPROVAL_REQUEST", link))).isTrue();
        }
        assertThat(early.allowsNotification(notification("APPROVAL_DONE", "/hr/documents/1"))).isFalse();
        assertThat(early.allowsNotification(notification("ADMIN_NOTICE", "/notices/1"))).isFalse();
        assertThat(early.allowsNotification(notification("KPI", "/eval/kpi"))).isFalse();
        assertThat(new ReleaseFeatures("0.4").allowsNotification(notification("KPI", "/eval/kpi"))).isFalse();
        assertThat(new ReleaseFeatures("0.6").allowsNotification(notification("MR_CREATED", "/deploy/request-detail?id=1"))).isFalse();
        assertThat(new ReleaseFeatures("0.6").allowsNotification(notification("CUSTOM", "/deploy/dashboard"))).isFalse();
        assertThat(new ReleaseFeatures("0.7").allowsNotification(notification("MR_CREATED", "/deploy/request-detail?id=1"))).isTrue();
        assertThat(new ReleaseFeatures("0.5").allowsNotification(notification("MESSAGE", "/dashboard?messageRoomId=1"))).isFalse();
        assertThat(new ReleaseFeatures("0.6").allowsNotification(notification("MESSAGE", "/dashboard?messageRoomId=1"))).isTrue();
        assertThat(new ReleaseFeatures("0.6").allowsNotification(notification("DEPLOY", "/deploy-system/sso"))).isFalse();
        assertThat(new ReleaseFeatures("0.7").allowsNotification(notification("DEPLOY", "/deploy-system/sso"))).isTrue();
    }

    @Test
    void encodedRoutesUseTheSameDecodedPathAsMvcBeforeCheckingTheEdition() throws Exception {
        for (String path : new String[]{"/admin/%6eotices", "/mypage/deploy%2dsso/link", "/%72eport/my", "/api/desktop/%6cogin"}) {
            var response = new MockHttpServletResponse();
            AtomicBoolean invoked = new AtomicBoolean();
            new ReleaseFeatureFilter(new ReleaseFeatures("0.1")).doFilter(new MockHttpServletRequest("POST", path), response,
                    (request, result) -> invoked.set(true));
            assertThat(invoked.get()).as(path).isFalse();
            assertThat(response.getStatus()).as(path).isEqualTo(404);
        }
    }

    @Test
    void systemPropertiesCannotUnlockPackagedEdition() {
        String edition = new ReleaseFeatures().getVersion();
        String previous = System.getProperty("app.release.version");
        try {
            System.setProperty("app.release.version", "0.1".equals(edition) ? "full" : "0.1");
            assertThat(new ReleaseFeatures().getVersion()).isEqualTo(edition);
        } finally {
            if (previous == null) System.clearProperty("app.release.version");
            else System.setProperty("app.release.version", previous);
        }
        assertThatThrownBy(() -> new ReleaseFeatures("0.9")).isInstanceOf(IllegalArgumentException.class);
    }

    private Notification notification(String type, String link) {
        Notification notification = new Notification();
        notification.setType(type);
        notification.setLinkUrl(link);
        return notification;
    }
}
