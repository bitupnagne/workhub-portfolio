package com.portfolio.worksystem.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/** Shared demo accounts may edit sample work, but not credentials or external integrations. */
public final class PortfolioPublicDemoPolicy {
    public static final String MESSAGE = "공개 데모에서는 계정·보안 설정 변경, 설치 파일 배포와 외부 시스템 연동을 사용할 수 없습니다.";
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private PortfolioPublicDemoPolicy() {}

    public static boolean isRestrictedRequest(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        if (under(path, "/api/internal", "/api/desktop", "/desktop/sso", "/deploy-system",
                "/mypage/deploy-sso", "/otp", "/admin/releases")) return true;
        return !READ_METHODS.contains(request.getMethod())
                && under(path, "/admin/system", "/admin/settings", "/admin/users", "/admin/permissions", "/mypage/password");
    }

    private static boolean under(String path, String... roots) {
        for (String root : roots) {
            if (path.equals(root) || path.startsWith(root + "/")) return true;
        }
        return false;
    }
}
