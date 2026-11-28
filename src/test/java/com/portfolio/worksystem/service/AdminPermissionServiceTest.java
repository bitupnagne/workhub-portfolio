package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.config.ReleaseFeatures;
import com.portfolio.worksystem.mapper.AdminMenuGrantMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.AdminMenu;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.AdminMenuContext;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** [PERM] 메뉴별 권한 — 누가 무엇을 가지고, 누가 줄 수 있는지, 메뉴 권한이 그 화면 밖으로 새지 않는지. */
class AdminPermissionServiceTest {
    private final AdminMenuGrantMapper grants = mock(AdminMenuGrantMapper.class);
    private final AdminPermissionService service = new AdminPermissionService(grants, mock(TeamMapper.class),
            mock(UserMapper.class), new ReleaseFeatures("full"));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void superAdminHasEveryMenuWithoutRows() {
        assertThat(service.menusFor(user(1L, "SUPER_ADMIN"))).contains(AdminMenu.PAYROLL, AdminMenu.SYSTEM);
        verify(grants, never()).findMenuKeysForUser(anyLong());
    }

    @Test
    void othersGetOnlyWhatWasGrantedAndNeverSuperOnlyMenus() {
        when(grants.findMenuKeysForUser(2L)).thenReturn(List.of("LEAVE_LEDGER", "SYSTEM"));
        CustomUserDetails hr = user(2L, "EMPLOYEE");

        assertThat(service.menusFor(hr)).containsExactly(AdminMenu.LEAVE_LEDGER);
        assertThat(service.entryUrl(hr)).isEqualTo("/hr/leave/ledger");
    }

    @Test
    void onlySuperAdminGivesOutThePermissionManagementPermission() {
        when(grants.findMenuKeysForUser(3L)).thenReturn(List.of("ADMIN_PERMISSIONS"));
        CustomUserDetails manager = user(3L, "EMPLOYEE");

        assertThat(service.canGrant(manager, AdminMenu.PAYROLL)).isTrue();
        assertThat(service.canGrant(manager, AdminMenu.ADMIN_PERMISSIONS)).isFalse();
        assertThat(service.canGrant(manager, AdminMenu.SYSTEM)).isFalse();
        assertThatThrownBy(() -> service.grant(manager, "ADMIN_PERMISSIONS", "USER", 9L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("슈퍼관리자");
        verify(grants, never()).insert(any());
    }

    @Test
    void menuRolesApplyOnlyInsideThatMenusRequest() {
        CustomUserDetails granted = user(4L, "EMPLOYEE");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(granted.hasRole("HR_ADMIN")).isFalse();
        AdminMenuContext.set(AdminMenu.LEAVE_LEDGER, 4L);
        assertThat(granted.hasRole("HR_ADMIN")).isTrue();
        // 같은 요청이라도 다른 사람에게는 번지지 않는다
        assertThat(user(5L, "EMPLOYEE").hasRole("HR_ADMIN")).isFalse();
        // 시스템 관리자로 보이지는 않는다
        assertThat(granted.isSystemAdmin()).isFalse();
    }

    private static CustomUserDetails user(Long id, String role) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setUsername("u" + id);
        account.setName("사용자" + id);
        account.setActive(true);
        account.setRoles(List.of(role));
        return new CustomUserDetails(account);
    }
}
