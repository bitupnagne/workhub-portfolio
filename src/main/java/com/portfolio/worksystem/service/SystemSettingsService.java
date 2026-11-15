package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.SystemThemeForm;
import com.portfolio.worksystem.mapper.SystemSettingsMapper;
import com.portfolio.worksystem.model.SystemBranding;
import com.portfolio.worksystem.model.SystemSetting;
import com.portfolio.worksystem.model.SystemStorageStatus;
import com.portfolio.worksystem.model.SystemTheme;
import com.portfolio.worksystem.model.SystemThemeProfile;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@Service
public class SystemSettingsService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures = new com.portfolio.worksystem.config.ReleaseFeatures();
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern SAFE_LOGO_FILENAME = Pattern.compile("^logo-[0-9a-fA-F-]{36}\\.(svg|png|jpg|jpeg|webp)$");
    private static final Set<String> LOGO_EXTENSIONS = Set.of("svg", "png", "jpg", "jpeg", "webp");
    private static final long MAX_LOGO_BYTES = 2L * 1024L * 1024L;
    private static final int THEME_PROFILE_COUNT = 5;
    private static final SystemTheme AMAZON_THEME = new SystemTheme(
            "#FF9900",
            "#232F3E",
            "#131A22",
            "#FFF3DF",
            "#FFE0B2",
            "#FFB84D"
    );

    private final SystemSettingsMapper systemSettingsMapper;

    @Value("${app.upload.logo-dir:uploads/system-logos}")
    private String logoDir;

    @Value("${LOGNAME:${USER:앱실행계정}}")
    private String runtimeUser;

    public SystemTheme theme() {
        Map<String, String> values = settings();
        return SystemTheme.from(values);
    }

    @Transactional
    public List<SystemThemeProfile> themeProfiles(CustomUserDetails user) {
        Long updatedBy = user == null ? null : user.getUserId();
        ensureThemeProfiles(updatedBy);
        Map<String, String> values = settings();
        List<SystemThemeProfile> profiles = new ArrayList<>();
        for (int i = 1; i <= THEME_PROFILE_COUNT; i++) {
            profiles.add(new SystemThemeProfile(i, "프로필 " + i, profileTheme(values, i)));
        }
        return profiles;
    }

    public int activeThemeProfile() {
        return parseProfile(settings().get("theme.activeProfile"));
    }

    @Transactional
    public void saveTheme(SystemThemeForm form, CustomUserDetails user) {
        SystemTheme theme = new SystemTheme(
                clean(form.brand(), "메인 색상"),
                clean(form.brandDark(), "진한 색상"),
                clean(form.brandDeeper(), "강조 진한 색상"),
                clean(form.brandLight(), "연한 배경 색상"),
                clean(form.brandLight2(), "보조 배경 색상"),
                clean(form.brandMuted(), "보조 테두리 색상")
        );
        Long updatedBy = user == null ? null : user.getUserId();
        putSetting("theme.brand", theme.brand(), updatedBy);
        putSetting("theme.brandDark", theme.brandDark(), updatedBy);
        putSetting("theme.brandDeeper", theme.brandDeeper(), updatedBy);
        putSetting("theme.brandLight", theme.brandLight(), updatedBy);
        putSetting("theme.brandLight2", theme.brandLight2(), updatedBy);
        putSetting("theme.brandMuted", theme.brandMuted(), updatedBy);
    }

    @Transactional
    public void saveCurrentThemeAsProfile(int profileNumber, CustomUserDetails user) {
        validateProfileNumber(profileNumber);
        Long updatedBy = user == null ? null : user.getUserId();
        saveProfile(profileNumber, theme(), updatedBy);
    }

    @Transactional
    public void loadThemeProfile(int profileNumber, CustomUserDetails user) {
        validateProfileNumber(profileNumber);
        Long updatedBy = user == null ? null : user.getUserId();
        ensureThemeProfiles(updatedBy);
        SystemTheme profile = profileTheme(settings(), profileNumber);
        saveActiveTheme(profile, updatedBy);
        putSetting("theme.activeProfile", String.valueOf(profileNumber), updatedBy);
    }

    /** 웹 테마 모드: light | dark | system(OS 설정 추종). 기본 light. */
    public String themeMode() {
        String m = settings().getOrDefault("theme.mode", "light");
        return switch (m) {
            case "dark", "system" -> m;
            default -> "light";
        };
    }

    public void saveThemeMode(String mode, CustomUserDetails user) {
        putSetting("theme.mode", normalizeMode(mode), user == null ? null : user.getUserId());
    }

    /** null/빈값이면 light 로 정규화. */
    public static String normalizeMode(String mode) {
        return switch (mode == null ? "" : mode.trim()) {
            case "dark", "system" -> mode.trim();
            case "light" -> "light";
            default -> "light";
        };
    }

    /** 관리자 기본 모드 사용. */
    public String themeCss() {
        return themeCss(null);
    }

    /**
     * 웹 테마 CSS. userOverride(사용자 개인 선택: dark|light|system|null)가 있으면 그것을,
     * 없으면 관리자 기본(theme.mode)을 따른다 → 각 사용자가 스스로 온오프 가능.
     */
    public String themeCss(String userOverride) {
        SystemTheme t = theme();
        // 단색 브랜드 면의 글자는 흰색으로 고정하고, 연한 틴트만 자동 대비를 사용한다.
        String base = """
                :root {
                  color-scheme: light;
                  --system-brand:%s;
                  --system-brand-dark:%s;
                  --system-brand-deeper:%s;
                  --system-brand-light:%s;
                  --system-brand-light2:%s;
                  --system-brand-muted:%s;
                  --system-on-brand:#FFFFFF;
                  --system-on-brand-dark:#FFFFFF;
                  --system-on-brand-deeper:#FFFFFF;
                  --system-on-brand-light:%s;
                  --system-on-brand-light2:%s;
                  --system-on-brand-muted:%s;
                  --system-control-border:color-mix(in srgb,var(--system-brand) 30%%,var(--system-border-2,#d1d5db));
                  --system-control-hover-border:var(--system-brand-dark);
                  --system-control-focus:light-dark(color-mix(in srgb,var(--system-brand) 45%%,black),color-mix(in srgb,var(--system-brand) 35%%,white));
                  --system-control-readonly-border:color-mix(in srgb,var(--system-brand) 20%%,var(--system-border-2,#d1d5db));
                  --system-control-readonly-bg:color-mix(in srgb,var(--system-brand) 5%%,var(--system-surface-2,#f1f5f9));
                }
                """.formatted(
                t.brand(),
                t.brandDark(),
                t.brandDeeper(),
                t.brandLight(),
                t.brandLight2(),
                t.brandMuted(),
                contrastText(t.brandLight()),
                contrastText(t.brandLight2()),
                contrastText(t.brandMuted())
        );
        // theme.mode 에 따라 중립(표면/텍스트/보더/그림자) 토큰을 다크로 스왑.
        // 브랜드 색은 admin 지정값을 유지하되, 옅은 브랜드 틴트(brand-light)는 다크 표면에 얹히도록
        // 어둡게 재정의하고 그 위 텍스트(on-brand-light)를 밝게 재계산 → 어떤 브랜드 색이든 대비 유지.
        // 사용자 개인 선택이 있으면 우선, 없으면 관리자 기본(theme.mode).
        String mode = (userOverride != null && !userOverride.isBlank())
                ? normalizeMode(userOverride)
                : themeMode();
        if ("dark".equals(mode)) {
            return base + ":root {\n" + DARK_NEUTRAL_TOKENS + "}\n";
        }
        if ("system".equals(mode)) {
            return base + "@media (prefers-color-scheme: dark) {\n  :root {\n" + DARK_NEUTRAL_TOKENS + "  }\n}\n";
        }
        return base;
    }

    /** 다크 중립 팔레트(브랜드 색과 무관, 표면/텍스트/보더/그림자 + 옅은 브랜드 틴트 반전). */
    private static final String DARK_NEUTRAL_TOKENS = """
                  color-scheme: dark;
                  --system-bg:#0d1117;
                  --system-surface:#1b2431;
                  --system-surface-2:#151d28;
                  --system-surface-3:#0f151e;
                  --system-text:#e6edf3;
                  --system-text-2:#c3ccd6;
                  --system-text-3:#98a2b0;
                  --system-text-faint:#6b7683;
                  --system-border:#2a3543;
                  --system-border-2:#374250;
                  --system-elev-1:0 1px 2px rgba(0,0,0,.55);
                  --system-elev-2:0 6px 18px rgba(0,0,0,.55);
                  --system-elev-3:0 14px 36px rgba(0,0,0,.62);
                  --system-brand-light:color-mix(in srgb,var(--system-brand) 12%,var(--system-surface));
                  --system-brand-light2:color-mix(in srgb,var(--system-brand) 20%,var(--system-surface));
                  --system-on-brand-light:#e6edf3;
                  --system-on-brand-light2:#e6edf3;
                  --system-nav-hover-text:color-mix(in srgb,var(--system-brand) 35%,white);
            """;

    public SystemBranding branding() {
        Map<String, String> values = settings();
        return new SystemBranding(
                logoUrl(values.get("logo.web"), "/img/AppLogo.svg"),
                logoUrl(values.get("logo.desktop"), "/img/DesktopLogo.svg")
        );
    }

    public Map<String, String> deployIntegration() {
        Map<String, String> values = settings();
        return Map.of(
                "deploy.base-url", blankIfNull(values.get("deploy.base-url")),
                "deploy.sso.shared-secret", blankIfNull(values.get("deploy.sso.shared-secret"))
        );
    }

    public String setting(String key, String defaultValue) {
        return settings().getOrDefault(key, defaultValue);
    }

    // ── OTP 정책(관리자가 켜고 끔) ──────────────────────────────
    /** 관리자(SYSTEM_ADMIN/SUPER_ADMIN) 계정에 OTP 필수. */
    public boolean otpRequireAdmin() {
        return "true".equalsIgnoreCase(setting("otp.require.admin", "false"));
    }

    /** 전 사용자에 OTP 필수. */
    public boolean otpRequireUser() {
        return "true".equalsIgnoreCase(setting("otp.require.user", "false"));
    }

    /** 외부망(DMZ) 접속 시 OTP 필수 — 기본 ON(기존 동작 유지). */
    public boolean otpRequireExternal() {
        return "true".equalsIgnoreCase(setting("otp.require.external", "true"));
    }

    @Transactional
    public void saveOtpPolicy(boolean requireAdmin, boolean requireUser, boolean requireExternal, CustomUserDetails user) {
        Long updatedBy = user == null ? null : user.getUserId();
        putSetting("otp.require.admin", String.valueOf(requireAdmin), updatedBy);
        putSetting("otp.require.user", String.valueOf(requireUser), updatedBy);
        putSetting("otp.require.external", String.valueOf(requireExternal), updatedBy);
    }

    // ── 업로드 허용 확장자 화이트리스트(관리자 관리) ──────────────
    // 실행파일·스크립트(exe·bat·js·sh 등)는 기본 미포함. 업무 파일은 넉넉히 허용.
    private static final String DEFAULT_ALLOWED_EXTENSIONS =
            "pdf,hwp,hwpx,doc,docx,xls,xlsx,ppt,pptx,txt,csv,png,jpg,jpeg,gif,bmp,webp,zip,7z";

    /** 채팅·자료전송 등 업로드 허용 확장자 집합(소문자, 점 제거). 미설정 시 기본값. */
    public Set<String> allowedUploadExtensions() {
        String csv = setting("upload.allowed.extensions", DEFAULT_ALLOWED_EXTENSIONS);
        if (!StringUtils.hasText(csv)) {
            csv = DEFAULT_ALLOWED_EXTENSIONS;
        }
        Set<String> set = new java.util.LinkedHashSet<>();
        for (String s : csv.split(",")) {
            String e = s.trim().toLowerCase().replaceFirst("^\\.", "");
            if (!e.isBlank()) {
                set.add(e);
            }
        }
        return set;
    }

    /** 관리 화면 표시용 원문(", " 구분). */
    public String allowedUploadExtensionsCsv() {
        return String.join(", ", allowedUploadExtensions());
    }

    @Transactional
    public void saveAllowedUploadExtensions(String csv, CustomUserDetails user) {
        Long updatedBy = user == null ? null : user.getUserId();
        Set<String> set = new java.util.LinkedHashSet<>();
        for (String s : (csv == null ? "" : csv).split("[,\\s]+")) {
            String e = s.trim().toLowerCase().replaceFirst("^\\.", "");
            if (!e.isBlank() && e.matches("[a-z0-9]{1,10}")) {
                set.add(e);
            }
        }
        if (set.isEmpty()) {
            throw new BusinessException("허용 확장자를 1개 이상 입력하세요.");
        }
        putSetting("upload.allowed.extensions", String.join(",", set), updatedBy);
    }

    /** 업로드 파일 확장자 검증 — 허용 목록에 없으면 예외(안내문구 포함). 채팅·자료전송 등에서 호출. */
    public void validateUploadExtension(String originalFilename) {
        String name = StringUtils.cleanPath(originalFilename == null ? "" : originalFilename);
        String ext = extension(name);
        if (ext.isBlank() || !allowedUploadExtensions().contains(ext)) {
            throw new BusinessException("허용되지 않은 파일 형식입니다. 필요 시 관리자에게 문의하세요."
                    + " (허용 형식: " + allowedUploadExtensionsCsv() + ")");
        }
    }

    // ── 외부망(DMZ) 메뉴 노출 정책 (그룹 + 하위메뉴 2depth, 스위치 ON=노출) ──────
    /** 외부망 메뉴 한 항목(하위메뉴). paths 는 직접 URL 접근 차단용 경로(문서함은 "?box=" 포함). */
    public record ExtMenuItem(String key, String label, List<String> paths) {}
    /** 외부망 메뉴 그룹(1depth) + 하위메뉴(2depth). */
    public record ExtMenuGroup(String key, String label, List<ExtMenuItem> children) {}

    private static ExtMenuItem extItem(String key, String label, String... paths) {
        return new ExtMenuItem(key, label, List.of(paths));
    }

    /** [UX46] 외부망 노출 제어 대상 메뉴 트리 — fragments/app 사이드바와 1:1 동기(단일 소스).
     *  사이드바 메뉴를 바꾸면 반드시 이 트리도 같이 갱신한다(그룹/키/라벨/경로).
     *  UX17 통합으로 내 휴가/근태/시차/출장은 hr.attendance 한 키가 4개 경로를 커버한다. */
    public static final List<ExtMenuGroup> EXTERNAL_MENU_TREE = List.of(
            // [UX49] 대시보드도 외부망 노출 제어 — 숨기면 외부망 로그인 후 런처(/launcher)가 홈이 된다
            new ExtMenuGroup("dashboard", "대시보드", List.of(
                    extItem("dashboard.home", "대시보드 화면", "/dashboard"))),
            new ExtMenuGroup("projects", "프로젝트 관리", List.of(
                    extItem("project.manage", "참여 프로젝트", "/projects"))),
            new ExtMenuGroup("reports", "주간보고 관리", List.of(
                    extItem("report.my", "내 주간보고", "/report/my"),
                    extItem("report.team", "팀 주간보고", "/report/team"),
                    extItem("report.admin", "전체 현황·취합", "/report/admin"),
                    extItem("report.search", "주간보고 검색", "/report/search"))),
            new ExtMenuGroup("organization", "조직 관리", List.of(
                    extItem("org.settings", "관리자 지정", "/admin/settings"),
                    extItem("org.org", "조직도 관리", "/admin/org"),
                    extItem("org.users", "직원 목록·등록", "/people", "/admin/users"),
                    extItem("org.approvalRoutes", "결재 경로 관리", "/admin/approval-routes"),
                    extItem("org.hrDiagnostics", "HR 운영 점검", "/admin/hr-diagnostics"))),
            new ExtMenuGroup("communication", "공지·소통", List.of(
                    extItem("communication.notices", "공지 관리", "/admin/notices"))),
            new ExtMenuGroup("super-admin", "슈퍼 관리자 메뉴", List.of(
                    extItem("super.releases", "앱 업데이트 관리", "/admin/releases"),
                    extItem("super.system", "시스템 관리", "/admin/system"))),
            new ExtMenuGroup("hr", "HR 관리", List.of(
                    extItem("hr.attendance", "휴가·근태 (휴가·시차·출장 통합)",
                            "/hr/attendance", "/hr/leave", "/hr/flex-time", "/hr/trips"),
                    extItem("hr.loa", "휴직", "/hr/loa"),
                    extItem("hr.schedules", "일정관리", "/hr/schedules"))),
            new ExtMenuGroup("documents", "문서 보관함", List.of(
                    extItem("doc.approval", "결재 문서함", "/hr/documents?box=all"),
                    extItem("doc.io", "발·수신 문서함", "/hr/documents?box=ioall"),
                    extItem("hr.delegations", "대리승인자 지정", "/hr/delegations"))),
            new ExtMenuGroup("members", "직원 HR 관리", List.of(
                    extItem("members.overview", "직원들 통합 현황", "/hr/members"),
                    extItem("members.matrix", "근태 매트릭스", "/hr/members/matrix"),
                    extItem("members.leaveLedger", "연차 관리", "/hr/leave/ledger"),
                    extItem("members.attendancePolicy", "근태 설정", "/hr/attendance/policy"))));

    private static final String EXT_PREFIX = "menu.ext.show.";
    /** [UX50] 내부망 노출 정책 키 접두사 — 외부망과 동일 구조. */
    private static final String INT_PREFIX = "menu.int.show.";

    private boolean shownKey(Map<String, String> values, String prefix, String key) {
        // 기본=노출(true). 명시적으로 "false" 일 때만 숨김.
        return !"false".equalsIgnoreCase(values.get(prefix + key));
    }

    private boolean extShown(Map<String, String> values, String key) {
        return shownKey(values, EXT_PREFIX, key);
    }

    /** 각 키(그룹/하위)의 '자체' 토글 ON(노출) 여부 — 관리 화면 체크 상태용(부모 영향 미반영). */
    public Set<String> externalShownMenus() {
        return shownMenus(EXT_PREFIX);
    }

    /** [UX50] 내부망 자체 토글 ON 키 — 관리 화면 체크 상태용. */
    public Set<String> internalShownMenus() {
        return shownMenus(INT_PREFIX);
    }

    private Set<String> shownMenus(String prefix) {
        Map<String, String> values = settings();
        Set<String> shown = new java.util.LinkedHashSet<>();
        for (ExtMenuGroup g : EXTERNAL_MENU_TREE) {
            if (shownKey(values, prefix, g.key())) shown.add(g.key());
            for (ExtMenuItem c : g.children()) {
                if (shownKey(values, prefix, c.key())) shown.add(c.key());
            }
        }
        return shown;
    }

    /** 외부망에서 '숨김'으로 효력 있는 키 집합(그룹/하위 모두). 그룹이 숨김이면 하위도 포함. 기본=노출. */
    public Set<String> externalHiddenMenus() {
        return hiddenMenus(EXT_PREFIX);
    }

    /** [UX50] 내부망 숨김 키(부모 그룹 영향 반영). */
    public Set<String> internalHiddenMenus() {
        return hiddenMenus(INT_PREFIX);
    }

    /** [UX50] 접속 존에 맞는 숨김 키 — 사이드바/런처 공용. */
    public Set<String> hiddenMenusFor(boolean external) {
        return external ? externalHiddenMenus() : internalHiddenMenus();
    }

    private Set<String> hiddenMenus(String prefix) {
        Map<String, String> values = settings();
        Set<String> hidden = new java.util.LinkedHashSet<>();
        for (ExtMenuGroup g : EXTERNAL_MENU_TREE) {
            boolean groupShown = shownKey(values, prefix, g.key());
            if (!groupShown) hidden.add(g.key());
            for (ExtMenuItem c : g.children()) {
                if (!groupShown || !shownKey(values, prefix, c.key())) hidden.add(c.key());
            }
        }
        return hidden;
    }

    /** Use the exact same edition-specific tree for rendering and saving so future settings are preserved. */
    public List<ExtMenuGroup> releaseMenuTree() {
        List<ExtMenuGroup> visible = EXTERNAL_MENU_TREE.stream()
                .filter(group -> !releaseFeatures.unavailableMenus().contains(group.key()))
                .map(group -> new ExtMenuGroup(group.key(), group.label(), group.children().stream()
                        .filter(item -> item.paths().stream().anyMatch(releaseFeatures::allowsPath)).toList()))
                .filter(group -> !group.children().isEmpty()).collect(Collectors.toCollection(ArrayList::new));
        if (!releaseFeatures.isAttendanceEnabled()) {
            visible.add(new ExtMenuGroup("members", "인사카드", List.of(new ExtMenuItem("members.overview", "개인 인사카드", List.of("/people/me")))));
        }
        return visible;
    }

    /** [UX50] 내/외부망 노출 정책 저장(체크=노출). */
    @Transactional
    public void saveMenuPolicy(List<String> extShownKeys, List<String> intShownKeys, CustomUserDetails user) {
        Long updatedBy = user == null ? null : user.getUserId();
        Set<String> ext = extShownKeys == null ? Set.of() : new java.util.HashSet<>(extShownKeys);
        Set<String> internal = intShownKeys == null ? Set.of() : new java.util.HashSet<>(intShownKeys);
        for (ExtMenuGroup g : releaseMenuTree()) {
            putSetting(EXT_PREFIX + g.key(), String.valueOf(ext.contains(g.key())), updatedBy);
            putSetting(INT_PREFIX + g.key(), String.valueOf(internal.contains(g.key())), updatedBy);
            for (ExtMenuItem c : g.children()) {
                putSetting(EXT_PREFIX + c.key(), String.valueOf(ext.contains(c.key())), updatedBy);
                putSetting(INT_PREFIX + c.key(), String.valueOf(internal.contains(c.key())), updatedBy);
            }
        }
    }

    /** 외부망에서 숨김인 메뉴의 경로면 true(직접 URL 접근 차단). box = /hr/documents 의 box 값(없으면 'all'). */
    /** [UX49] 외부망에서 대시보드가 숨김인지 — 로그인 후/차단 시 홈을 런처로 바꿀 때 사용. */
    public boolean isDashboardExternallyHidden() {
        return isDashboardHidden(true);
    }

    /** [UX50] 접속 존 기준 대시보드 숨김 여부. */
    public boolean isDashboardHidden(boolean external) {
        Set<String> hidden = hiddenMenusFor(external);
        return hidden.contains("dashboard") || hidden.contains("dashboard.home");
    }

    public boolean isExternallyBlockedPath(String path, String box) {
        return isBlockedPath(true, path, box);
    }

    /** [UX50] 접속 존 기준 직접 URL 차단 여부 — 내부망은 internalHiddenMenus 를 본다. */
    public boolean isBlockedPath(boolean external, String path, String box) {
        if (path == null) return false;
        // 공통 홈은 메뉴 숨김 정책의 복귀 지점이므로 차단하지 않는다.
        if ("/org".equals(path)) return false;
        // 본인/대상별 인사카드는 기존 HR 인사카드 노출 정책을 그대로 따른다.
        if (path.startsWith("/people/")) path = "/hr/members/" + path.substring("/people/".length());
        Set<String> hidden = hiddenMenusFor(external);
        if (hidden.isEmpty()) return false;
        // 결재 문서 보기·결재 처리(/hr/leave/{id}/document·approve 등)는 '결재 문서함' 소속 —
        // HR 메뉴(hr.leave 등)를 외부망에서 숨겨도 문서함이 노출이면 문서는 열려야 한다.
        if (path.matches("/hr/(leave|trips|flex-time)/\\d+(/.*)?")
                || path.matches("/hr/trips/report/\\d+(/.*)?")
                || path.matches("/hr/attendance/correction/\\d+(/.*)?")) {
            return hidden.contains("doc.approval");
        }
        // [UX50b] 경로 접두사 겹침 교차차단 방지 — 요청 경로를 '가장 구체적으로' 소유한 메뉴 항목을
        //   먼저 찾고(최장 매칭), 그 소유 항목이 숨김일 때만 차단한다.
        //   예: '/hr/leave/ledger'(연차 관리)는 '/hr/leave'(휴가·근태)보다 더 길게 매칭되므로,
        //       휴가·근태를 숨겨도 연차 관리가 노출이면 접근이 허용된다.
        String effBox = (box == null || box.isBlank()) ? "all" : box;
        String bestPath = null;
        String bestKey = null;
        for (ExtMenuGroup g : EXTERNAL_MENU_TREE) {
            for (ExtMenuItem c : g.children()) {
                for (String spec : c.paths()) {
                    String p = spec;
                    String reqBox = null;
                    int qi = spec.indexOf("?box=");
                    if (qi >= 0) {
                        p = spec.substring(0, qi);
                        reqBox = spec.substring(qi + 5);
                    }
                    boolean pathMatch = path.equals(p) || path.startsWith(p + "/");
                    if (!pathMatch) continue;
                    if (reqBox != null && !reqBox.equals(effBox)) continue; // box 불일치 매핑은 소유권 없음
                    if (bestPath == null || p.length() > bestPath.length()) {
                        bestPath = p;
                        bestKey = c.key();
                    }
                }
            }
        }
        // 트리에 없는 경로는 정책 대상 아님(통과). 소유 항목이 숨김이면 차단.
        return bestKey != null && hidden.contains(bestKey);
    }

    @Transactional
    public void saveDeployIntegration(String deployBaseUrl, String sharedSecret, CustomUserDetails user) {
        Long updatedBy = user == null ? null : user.getUserId();
        putSetting("deploy.base-url", normalizeUrl(deployBaseUrl), updatedBy);
        putSetting("deploy.sso.shared-secret", sharedSecret == null ? "" : sharedSecret.trim(), updatedBy);
    }

    public SystemStorageStatus logoStorageStatus() {
        return storageStatus("시스템 로고 저장소", logoRoot());
    }

    @Transactional
    public void saveLogos(MultipartFile webLogo, MultipartFile desktopLogo, CustomUserDetails user) {
        ensureLogoStorageReady();
        Long updatedBy = user == null ? null : user.getUserId();
        if (webLogo != null && !webLogo.isEmpty()) {
            putSetting("logo.web", storeLogo(webLogo), updatedBy);
        }
        if (desktopLogo != null && !desktopLogo.isEmpty()) {
            putSetting("logo.desktop", storeLogo(desktopLogo), updatedBy);
        }
    }

    public StoredLogo loadLogo(String filename) {
        String safe = validateLogoFilename(filename);
        Path root = logoRoot();
        Path file = root.resolve(safe).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BusinessException("로고 파일을 찾을 수 없습니다.");
        }
        try {
            return new StoredLogo(new UrlResource(file.toUri()), mediaTypeFor(safe));
        } catch (IOException e) {
            throw new BusinessException("로고 파일을 읽을 수 없습니다.");
        }
    }

    private String storeLogo(MultipartFile file) {
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw new BusinessException("로고 파일은 2MB 이하만 등록할 수 있습니다.");
        }
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String ext = extension(original);
        if (!LOGO_EXTENSIONS.contains(ext)) {
            throw new BusinessException("로고는 SVG, PNG, JPG, WEBP 파일만 등록할 수 있습니다.");
        }
        Path root = logoRoot();
        String filename = "logo-" + UUID.randomUUID() + "." + ext;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("로고 저장 경로가 올바르지 않습니다.\n" + storageHelp(root));
        }
        try {
            Files.createDirectories(root);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException("로고 파일 저장에 실패했습니다.\n" + storageHelp(root));
        }
        com.portfolio.worksystem.util.AfterCommit.deleteUploadOnRollback(target, "로고");
        return filename;
    }

    private void ensureLogoStorageReady() {
        SystemStorageStatus status = logoStorageStatus();
        if (status.exists() && status.directory() && status.writable()) {
            return;
        }
        throw new BusinessException(status.message() + "\n" + status.fixCommand());
    }

    private SystemStorageStatus storageStatus(String label, Path root) {
        boolean exists = Files.exists(root);
        boolean directory = Files.isDirectory(root);
        boolean writable = directory && Files.isWritable(root);
        String message;
        if (!exists) {
            message = label + " 디렉터리가 없습니다.";
        } else if (!directory) {
            message = label + " 경로가 디렉터리가 아닙니다.";
        } else if (!writable) {
            message = label + "에 쓰기 권한이 없습니다.";
        } else {
            message = label + "가 정상입니다.";
        }
        return new SystemStorageStatus(label, root.toString(), exists, directory, writable, message, storageHelp(root));
    }

    private String storageHelp(Path root) {
        String path = root.toString();
        String user = StringUtils.hasText(runtimeUser) ? runtimeUser : "앱실행계정";
        return """
                경로: %s
                해결:
                sudo mkdir -p %s
                sudo chown -R %s:%s %s
                sudo chmod 755 %s
                """.formatted(path, path, user, user, path, path).trim();
    }

    private String logoUrl(String filename, String fallback) {
        if (!StringUtils.hasText(filename)) {
            return fallback;
        }
        return "/system-logos/" + validateLogoFilename(filename);
    }

    private Path logoRoot() {
        return Path.of(logoDir).toAbsolutePath().normalize();
    }

    private String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String validateLogoFilename(String filename) {
        String safe = StringUtils.cleanPath(filename == null ? "" : filename);
        if (!SAFE_LOGO_FILENAME.matcher(safe).matches()) {
            throw new BusinessException("로고 파일명이 올바르지 않습니다.");
        }
        return safe;
    }

    private static String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1).toLowerCase();
    }

    private static MediaType mediaTypeFor(String filename) {
        String ext = extension(filename);
        return switch (ext) {
            case "svg" -> MediaType.valueOf("image/svg+xml");
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "webp" -> MediaType.valueOf("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private String clean(String value, String label) {
        String color = value == null ? "" : value.trim();
        if (!HEX_COLOR.matcher(color).matches()) {
            throw new BusinessException(label + "은 #RRGGBB 형식이어야 합니다.");
        }
        return color.toUpperCase();
    }

    private void ensureThemeProfiles(Long updatedBy) {
        Map<String, String> values = settings();
        SystemTheme current = SystemTheme.from(values);
        if (!hasProfile(values, 1)) {
            saveProfile(1, current, updatedBy);
        }
        if (!hasProfile(values, 2)) {
            saveProfile(2, AMAZON_THEME, updatedBy);
        }
        for (int i = 3; i <= THEME_PROFILE_COUNT; i++) {
            if (!hasProfile(values, i)) {
                saveProfile(i, current, updatedBy);
            }
        }
        if (!StringUtils.hasText(values.get("theme.activeProfile"))) {
            putSetting("theme.activeProfile", "1", updatedBy);
        }
    }

    private boolean hasProfile(Map<String, String> values, int profileNumber) {
        return StringUtils.hasText(values.get(profileKey(profileNumber, "brand")))
                && StringUtils.hasText(values.get(profileKey(profileNumber, "brandDark")))
                && StringUtils.hasText(values.get(profileKey(profileNumber, "brandDeeper")))
                && StringUtils.hasText(values.get(profileKey(profileNumber, "brandLight")))
                && StringUtils.hasText(values.get(profileKey(profileNumber, "brandLight2")))
                && StringUtils.hasText(values.get(profileKey(profileNumber, "brandMuted")));
    }

    private SystemTheme profileTheme(Map<String, String> values, int profileNumber) {
        SystemTheme fallback = profileNumber == 2 ? AMAZON_THEME : SystemTheme.defaults();
        return new SystemTheme(
                values.getOrDefault(profileKey(profileNumber, "brand"), fallback.brand()),
                values.getOrDefault(profileKey(profileNumber, "brandDark"), fallback.brandDark()),
                values.getOrDefault(profileKey(profileNumber, "brandDeeper"), fallback.brandDeeper()),
                values.getOrDefault(profileKey(profileNumber, "brandLight"), fallback.brandLight()),
                values.getOrDefault(profileKey(profileNumber, "brandLight2"), fallback.brandLight2()),
                values.getOrDefault(profileKey(profileNumber, "brandMuted"), fallback.brandMuted())
        );
    }

    private void saveActiveTheme(SystemTheme theme, Long updatedBy) {
        putSetting("theme.brand", theme.brand(), updatedBy);
        putSetting("theme.brandDark", theme.brandDark(), updatedBy);
        putSetting("theme.brandDeeper", theme.brandDeeper(), updatedBy);
        putSetting("theme.brandLight", theme.brandLight(), updatedBy);
        putSetting("theme.brandLight2", theme.brandLight2(), updatedBy);
        putSetting("theme.brandMuted", theme.brandMuted(), updatedBy);
    }

    private void saveProfile(int profileNumber, SystemTheme theme, Long updatedBy) {
        putSetting(profileKey(profileNumber, "brand"), theme.brand(), updatedBy);
        putSetting(profileKey(profileNumber, "brandDark"), theme.brandDark(), updatedBy);
        putSetting(profileKey(profileNumber, "brandDeeper"), theme.brandDeeper(), updatedBy);
        putSetting(profileKey(profileNumber, "brandLight"), theme.brandLight(), updatedBy);
        putSetting(profileKey(profileNumber, "brandLight2"), theme.brandLight2(), updatedBy);
        putSetting(profileKey(profileNumber, "brandMuted"), theme.brandMuted(), updatedBy);
    }

    private static String profileKey(int profileNumber, String colorKey) {
        return "theme.profile." + profileNumber + "." + colorKey;
    }

    private static void validateProfileNumber(int profileNumber) {
        if (profileNumber < 1 || profileNumber > THEME_PROFILE_COUNT) {
            throw new BusinessException("색상 프로필은 1~5번만 사용할 수 있습니다.");
        }
    }

    private static int parseProfile(String value) {
        try {
            int profile = Integer.parseInt(value == null ? "" : value.trim());
            return profile >= 1 && profile <= THEME_PROFILE_COUNT ? profile : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String contrastText(String hex) {
        String color = hex == null ? "#000000" : hex.replace("#", "");
        if (color.length() != 6) {
            return "#FFFFFF";
        }
        int r = Integer.parseInt(color.substring(0, 2), 16);
        int g = Integer.parseInt(color.substring(2, 4), 16);
        int b = Integer.parseInt(color.substring(4, 6), 16);
        double luminance = 0.2126 * linearChannel(r) + 0.7152 * linearChannel(g) + 0.0722 * linearChannel(b);
        double whiteContrast = 1.05 / (luminance + 0.05);
        double darkLuminance = 0.2126 * linearChannel(17) + 0.7152 * linearChannel(24) + 0.0722 * linearChannel(39);
        double darkContrast = (Math.max(luminance, darkLuminance) + 0.05) / (Math.min(luminance, darkLuminance) + 0.05);
        if (whiteContrast >= 4.5 && whiteContrast >= darkContrast) return "#FFFFFF";
        return darkContrast >= 4.5 ? "#111827" : "#000000";
    }

    private static double linearChannel(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    // [UX50b] settings 캐시 — 메뉴 가드가 매 요청 조회하므로 짧은 TTL 캐시(설정은 거의 안 바뀜).
    //   쓰기(upsert) 시 즉시 무효화하고, TTL은 미처 무효화 못 한 경로에 대한 안전망.
    private static final long SETTINGS_TTL_MS = 3000L;
    private volatile Map<String, String> settingsCache;
    private volatile long settingsCachedAt;

    private Map<String, String> settings() {
        Map<String, String> cached = settingsCache;
        if (cached != null && System.currentTimeMillis() - settingsCachedAt < SETTINGS_TTL_MS) {
            return cached;
        }
        Map<String, String> fresh = systemSettingsMapper.findAll().stream()
                .collect(Collectors.toMap(SystemSetting::getSettingKey,
                        s -> s.getSettingValue() == null ? "" : s.getSettingValue()));
        settingsCache = fresh;
        settingsCachedAt = System.currentTimeMillis();
        return fresh;
    }

    /** 설정 저장 후 캐시 즉시 무효화(관리자 저장이 바로 반영되도록). */
    private void invalidateSettingsCache() {
        settingsCache = null;
    }

    /** upsert + 캐시 무효화 — 모든 설정 쓰기가 이 래퍼를 타 캐시 누락을 방지한다. */
    private void putSetting(String key, String value, Long updatedBy) {
        systemSettingsMapper.upsert(key, value, updatedBy);
        invalidateSettingsCache();
    }

    public record StoredLogo(Resource resource, MediaType mediaType) {
    }
}
