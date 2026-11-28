package com.portfolio.worksystem.model;

import com.portfolio.worksystem.config.ReleaseFeatures;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 관리자 메뉴 하나 = 권한 하나.
 *
 * <p>예전에는 '인사 관리자' 같은 역할 하나가 근태 설정·연차·결재 경로·직원 정보를 통째로 가졌다. 이제는 메뉴마다
 * 사람이나 조직장 자리에 따로 준다({@code admin_menu_grants}). 슈퍼관리자는 모든 메뉴를 가진다.
 *
 * <ul>
 *   <li>{@code paths} — 이 메뉴의 주소. 가장 긴 접두어가 이긴다({@code /hr/leave/ledger} 는 {@code /hr/leave} 보다 먼저).
 *       {@code =} 로 시작하면 그 주소 하나만이다.</li>
 *   <li>{@code impliedRoles} — 이 메뉴 화면 <b>안에서</b> 기존 역할 검사(canManageHr 등)를 통과시킬 역할.
 *       메뉴 권한만 받은 사람이 자기 메뉴 화면에서 막히지 않게 하되, 다른 화면에서는 그 역할로 보지 않는다.</li>
 *   <li>{@code seedRoles} — 전환할 때 이 역할을 가진 사람에게 미리 넣어 준 권한(V91). 기록용.</li>
 *   <li>{@code grant} — 누가 이 권한을 줄 수 있는지.</li>
 * </ul>
 */
public enum AdminMenu {
    ADMIN_PERMISSIONS("관리자 권한 관리", "조직 관리", "/admin/permissions", List.of("/admin/permissions"),
            Set.of(), Grant.SUPER_ONLY, features -> true),
    ORG_CHART("조직도 관리", "조직 관리", "/admin/org", List.of("/admin/org"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, features -> true),
    USERS("직원 등록·관리", "조직 관리", "/admin/users", List.of("/admin/users", "=/people"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, features -> true),
    APPROVAL_ROUTES("결재 경로 관리", "조직 관리", "/admin/approval-routes", List.of("/admin/approval-routes"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, ReleaseFeatures::isAttendanceEnabled),
    HR_DIAGNOSTICS("HR 운영 점검", "조직 관리", "/admin/hr-diagnostics", List.of("/admin/hr-diagnostics"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, ReleaseFeatures::isAttendanceEnabled),
    GRADES("직급 관리", "인사·급여", "/admin/grades", List.of("/admin/grades"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, features -> true),
    PAYROLL("급여 관리", "인사·급여", "/admin/payroll", List.of("/admin/payroll"),
            Set.of(), Grant.MANAGERS, features -> true),
    PERSONNEL_TRANSFER("인사이동", "인사·급여", "/admin/personnel/transfer", List.of("/admin/personnel"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, features -> true),
    LEAVE_LEDGER("연차 관리", "근태 관리", "/hr/leave/ledger", List.of("/hr/leave/ledger"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, ReleaseFeatures::isAttendanceEnabled),
    ATTENDANCE_POLICY("근태 설정", "근태 관리", "/hr/attendance/policy", List.of("/hr/attendance/policy", "/hr/leave-types"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, ReleaseFeatures::isAttendanceEnabled),
    OVERTIME_SETTINGS("시간외 산정 기준", "근태 관리", "/hr/overtime/settings", List.of("/hr/overtime/settings"),
            Set.of("HR_ADMIN"), Grant.MANAGERS, ReleaseFeatures::isAttendanceEnabled),
    NOTICES("공지 관리", "소통", "/admin/notices", List.of("/admin/notices"),
            Set.of("NOTICE_ADMIN"), Grant.MANAGERS, ReleaseFeatures::isNoticesEnabled),
    SYSTEM("시스템 관리", "슈퍼관리자", "/admin/system", List.of("/admin/system"),
            Set.of(), Grant.NOBODY, features -> true),
    RELEASES("앱 업데이트 관리", "슈퍼관리자", "/admin/releases", List.of("/admin/releases"),
            Set.of(), Grant.NOBODY, ReleaseFeatures::isDesktopEnabled),
    LEGACY_ROLES("업무 역할 지정", "슈퍼관리자", "/admin/settings", List.of("/admin/settings"),
            Set.of(), Grant.NOBODY, features -> true);

    /** 누가 이 메뉴 권한을 줄 수 있는가. */
    public enum Grant {
        /** 슈퍼관리자와 '관리자 권한 관리' 권한자. */
        MANAGERS,
        /** 슈퍼관리자만 — 권한을 나눠 주는 권한이 퍼지지 않게. */
        SUPER_ONLY,
        /** 아무도 못 준다 — 슈퍼관리자 전용 메뉴. */
        NOBODY
    }

    private final String label;
    private final String group;
    private final String url;
    private final List<String> paths;
    private final Set<String> impliedRoles;
    private final Grant grant;
    private final Predicate<ReleaseFeatures> edition;

    AdminMenu(String label, String group, String url, List<String> paths, Set<String> impliedRoles,
              Grant grant, Predicate<ReleaseFeatures> edition) {
        this.label = label;
        this.group = group;
        this.url = url;
        this.paths = paths;
        this.impliedRoles = impliedRoles;
        this.grant = grant;
        this.edition = edition;
    }

    public String getKey() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public String getGroup() {
        return group;
    }

    public String getUrl() {
        return url;
    }

    public Set<String> getImpliedRoles() {
        return impliedRoles;
    }

    public Grant getGrant() {
        return grant;
    }

    public boolean isGrantable() {
        return grant != Grant.NOBODY;
    }

    public boolean isSuperOnlyGrant() {
        return grant == Grant.SUPER_ONLY;
    }

    public boolean isAvailable(ReleaseFeatures features) {
        return edition.test(features);
    }

    /** 이 주소를 지키는 메뉴 — 가장 긴 접두어. 관리자 메뉴 주소가 아니면 빈 값. */
    public static Optional<AdminMenu> forPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        AdminMenu best = null;
        int bestLength = -1;
        for (AdminMenu menu : values()) {
            for (String pattern : menu.paths) {
                boolean exact = pattern.startsWith("=");
                String base = exact ? pattern.substring(1) : pattern;
                boolean hit = exact ? path.equals(base) : (path.equals(base) || path.startsWith(base + "/"));
                if (hit && base.length() > bestLength) {
                    best = menu;
                    bestLength = base.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public static Optional<AdminMenu> of(String key) {
        return Arrays.stream(values()).filter(menu -> menu.name().equals(key)).findFirst();
    }

    /** 화면 순서(선언 순서). */
    public static List<AdminMenu> ordered() {
        return Arrays.stream(values()).sorted(Comparator.comparingInt(Enum::ordinal)).toList();
    }
}
