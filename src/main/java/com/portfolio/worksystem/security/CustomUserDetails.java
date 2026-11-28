package com.portfolio.worksystem.security;

import com.portfolio.worksystem.model.UserAccount;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class CustomUserDetails implements UserDetails {
    private final UserAccount user;
    private final String loginUsername;
    private final List<GrantedAuthority> authorities;
    private final boolean accountNonLocked;

    public CustomUserDetails(UserAccount user) {
        this(user, true);
    }

    public CustomUserDetails(UserAccount user, boolean accountNonLocked) {
        this.user = user;
        this.loginUsername = user.getUsername();
        this.accountNonLocked = accountNonLocked;
        java.util.List<GrantedAuthority> granted = new java.util.ArrayList<>();
        for (String role : user.getRoles()) {
            granted.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        if (user.hasRole("SUPER_ADMIN") && !user.hasRole("SYSTEM_ADMIN")) {
            granted.add(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
        }
        this.authorities = List.copyOf(granted);
    }

    public Long getUserId() {
        return user.getId();
    }

    /** [UX35b] 초기/재설정 비밀번호 사용 중 — 로그인 시점 기준. 서버 강제 가드가 사용. */
    public boolean isMustChangePassword() {
        return Boolean.TRUE.equals(user.getMustChangePassword());
    }

    /** 비밀번호 변경 완료 시 세션 principal 플래그를 내려 강제 가드 루프를 푼다. */
    public void markPasswordChanged() {
        user.setMustChangePassword(false);
    }

    public String getName() {
        return user.getName();
    }

    public Long getTeamId() {
        return user.getTeamId();
    }

    public String getTeamCode() {
        return user.getTeamCode();
    }

    public String getTeamName() {
        return user.getTeamName();
    }

    public String getEmployeeNumber() {
        return user.getEmployeeNumber();
    }

    public String getDept() {
        return user.getDept();
    }

    public String getExtensionNumber() {
        return user.getExtensionNumber();
    }

    public String getMobileNumber() {
        return user.getMobileNumber();
    }

    public String getJobTitle() {
        return user.getJobTitle();
    }

    public Long getGradeId() {
        return user.getGradeId();
    }

    public String getGradeName() {
        return user.getGradeName();
    }

    public String getEmail() {
        return user.getEmail();
    }

    public java.time.LocalDate getHireDate() {
        return user.getHireDate();
    }

    public String getProfileImageFilename() {
        return user.getProfileImageFilename();
    }

    public void updateProfileImageFilename(String profileImageFilename) {
        user.setProfileImageFilename(profileImageFilename);
    }

    public void updateBasicInfo(String dept, String extensionNumber, String mobileNumber, String jobTitle, String email) {
        user.setDept(dept);
        user.setExtensionNumber(extensionNumber);
        user.setMobileNumber(mobileNumber);
        user.setJobTitle(jobTitle);
        user.setEmail(email);
    }

    // 화면 표시용 정보(이름/사진/소속 등)를 DB 최신값으로 동기화한다. 권한/비밀번호는 건드리지 않는다.
    public void syncDisplay(UserAccount fresh) {
        if (fresh == null) {
            return;
        }
        user.setName(fresh.getName());
        user.setTeamId(fresh.getTeamId());
        user.setTeamCode(fresh.getTeamCode());
        user.setTeamName(fresh.getTeamName());
        user.setDept(fresh.getDept());
        user.setJobTitle(fresh.getJobTitle());
        user.setGradeId(fresh.getGradeId());
        user.setGradeName(fresh.getGradeName());
        user.setExtensionNumber(fresh.getExtensionNumber());
        user.setMobileNumber(fresh.getMobileNumber());
        user.setEmail(fresh.getEmail());
        user.setProfileImageFilename(fresh.getProfileImageFilename());
    }

    public boolean isOtpEnabled() {
        return user.isOtpEnabled();
    }

    public String getOtpSecret() {
        return user.getOtpSecret();
    }

    /** 등록/해제 후 세션 principal 의 OTP 상태를 즉시 반영. */
    public void applyOtpState(String secret, boolean enabled) {
        user.setOtpSecret(secret);
        user.setOtpEnabled(enabled);
    }

    public boolean hasRole(String role) {
        if (user.hasRole(role)) {
            return true;
        }
        if ("SYSTEM_ADMIN".equals(role) && user.hasRole("SUPER_ADMIN")) {
            return true;
        }
        // [PERM] 메뉴 권한으로 들어온 요청 안에서만 그 메뉴의 역할을 가진 것으로 본다.
        return AdminMenuContext.implies(user.getId(), role);
    }

    public boolean isSuperAdmin() {
        return user.hasRole("SUPER_ADMIN");
    }

    public boolean isSystemAdmin() {
        return hasRole("SYSTEM_ADMIN");
    }

    public boolean isTeamLeader() {
        return hasRole("TEAM_LEADER");
    }

    public boolean isEmployee() {
        return hasRole("EMPLOYEE");
    }

    /** 관리자 성격의 롤. 관리자 OTP 필수 정책 판정에 사용. */
    private static final List<String> ADMIN_ROLES = List.of(
            "SUPER_ADMIN", "SYSTEM_ADMIN", "REPORT_ADMIN", "HR_ADMIN", "ERP_ADMIN", "NOTICE_ADMIN");

    /** 관리자 롤을 하나라도 보유했는지 — 관리자 OTP 필수 정책에서 시스템관리자에 국한하지 않기 위함. */
    public boolean hasAnyAdminRole() {
        return ADMIN_ROLES.stream().anyMatch(user::hasRole) || AdminGrantLookup.hasAnyGrant(user.getId());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return loginUsername;
    }

    // SessionRegistry는 principal을 Map의 키로 사용한다. 표시 정보가 갱신되어도 로그인 계정의 동일성은 유지한다.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof CustomUserDetails details
                && loginUsername != null
                && loginUsername.equals(details.loginUsername);
    }

    @Override
    public int hashCode() {
        return loginUsername != null ? loginUsername.hashCode() : System.identityHashCode(this);
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked && Boolean.TRUE.equals(user.getActive());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(user.getActive());
    }
}
