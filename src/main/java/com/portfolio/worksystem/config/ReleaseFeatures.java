package com.portfolio.worksystem.config;

import com.portfolio.worksystem.model.Notification;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Immutable edition shipped with the source snapshot; environment variables cannot unlock features. */
@Component
public class ReleaseFeatures {
    private final int stage;
    private final String version;

    public ReleaseFeatures() {
        this(readEdition());
    }

    public ReleaseFeatures(String version) {
        this.version = version;
        this.stage = switch (version) {
            case "full" -> 8;
            case "0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8" -> version.charAt(2) - '0';
            default -> throw new IllegalArgumentException("지원하지 않는 릴리스 버전: " + version);
        };
    }

    private static String readEdition() {
        Properties properties = new Properties();
        try (var input = ReleaseFeatures.class.getResourceAsStream("/release-edition.properties")) {
            if (input == null) return "full";
            properties.load(input);
            String edition = properties.getProperty("release.version");
            if (edition == null || edition.isBlank()) throw new IllegalStateException("release.version이 필요합니다.");
            return edition.trim();
        } catch (IOException exception) {
            throw new IllegalStateException("릴리스 구성을 읽을 수 없습니다.", exception);
        }
    }

    public String getVersion() { return version; }
    public boolean isAttendanceEnabled() { return stage >= 2; }
    public boolean isReportsEnabled() { return stage >= 3; }
    public boolean isNoticesEnabled() { return stage >= 4; }
    public boolean isDashboardEnabled() { return stage >= 5; }
    public boolean isPresenceEnabled() { return stage >= 5; }
    public boolean isMessagingEnabled() { return stage >= 6; }
    public boolean isDeployEnabled() { return stage >= 7; }
    public boolean isDesktopEnabled() { return stage >= 8; }
    public String getHomeUrl() { return "/org"; }

    public boolean allowsPath(String path) {
        if (path == null) return false;
        if (any(path, "/eval", "/admin/eval", "/admin/grades", "/admin/personnel/transfer",
                "/hr/performance", "/api/desktop/eval", "/admin/org/kpi-target")) return false;
        if (!isAttendanceEnabled() && (any(path, "/hr", "/api/hr", "/my-work", "/api/notifications", "/admin/approval-routes", "/admin/hr-diagnostics",
                "/admin/system/approval-snapshots", "/mypage/signature", "/signature-images", "/admin/users/update")
                || path.matches("/admin/users/[^/]+/signature(?:/.*)?"))) return false;
        if (!isDesktopEnabled() && any(path, "/api/desktop", "/desktop/sso", "/admin/releases")) return false;
        if (!isDeployEnabled() && any(path, "/api/internal/deploy", "/deploy-system", "/mypage/deploy-sso", "/admin/system/deploy-integration")) return false;
        if (!isMessagingEnabled() && any(path, "/api/messages", "/api/work-memos", "/work-memos")) return false;
        if (!isDashboardEnabled() && any(path, "/dashboard")) return false;
        if (!isNoticesEnabled() && any(path, "/notices", "/notifications", "/admin/notices", "/notice-images")) return false;
        return isReportsEnabled() || !any(path, "/projects", "/report", "/admin/org/weekly-target");
    }

    private static boolean any(String path, String... prefixes) {
        for (String prefix : prefixes) if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        return false;
    }

    public boolean allowsNotification(Notification notification) {
        if (notification == null || !isAttendanceEnabled()) return false;
        String type = notification.getType() == null ? "" : notification.getType();
        if (type.startsWith("EVAL") || type.equals("KPI") || type.equals("PERFORMANCE_REVIEW")) return false;
        String link = notification.getLinkUrl() == null ? "" : notification.getLinkUrl().split("[?#]", 2)[0];
        if (!isMessagingEnabled() && ("MESSAGE".equals(type) || "WORK_MEMO".equals(type))) return false;
        if (!isDeployEnabled() && (type.startsWith("DEPLOY") || type.startsWith("MR_")
                || any(link, "/deploy"))) return false;
        if (!link.isEmpty() && !allowsPath(link)) return false;
        if (isNoticesEnabled()) return true;
        return type.startsWith("APPROVAL_") && any(link, "/hr/attendance", "/hr/leave", "/hr/loa", "/hr/flex-time", "/hr/trips");
    }

    public Set<String> unavailableMenus() {
        Set<String> menus = new LinkedHashSet<>();
        if (!isAttendanceEnabled()) menus.addAll(Set.of("hr", "documents", "members", "org.approvalRoutes", "org.hrDiagnostics"));
        if (!isReportsEnabled()) menus.addAll(Set.of("projects", "project.manage", "reports", "report.my", "report.team", "report.admin", "report.search"));
        if (!isNoticesEnabled()) menus.addAll(Set.of("communication", "communication.notices"));
        if (!isDashboardEnabled()) menus.add("dashboard");
        if (!isDesktopEnabled()) menus.add("super.releases");
        return menus;
    }
}
