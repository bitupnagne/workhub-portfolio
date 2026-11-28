package com.portfolio.worksystem.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.model.AdminMenu;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.AdminPermissionService;
import java.util.Map;
import java.util.Set;

/**
 * [PERM] 보안 설정을 띄우는 테스트용 — V91 이 전환하며 넣어 준 상태(기존 역할 = 같은 메뉴 권한)를 흉내 낸다.
 * 메뉴 권한 자체의 규칙은 AdminMenuTest·AdminPermissionServiceTest 가 본다.
 */
public final class AdminPermissionStub {
    private static final Map<AdminMenu, Set<String>> LEGACY = Map.ofEntries(
            Map.entry(AdminMenu.USERS, Set.of("SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN")),
            Map.entry(AdminMenu.ORG_CHART, Set.of("SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN")),
            Map.entry(AdminMenu.APPROVAL_ROUTES, Set.of("SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN")),
            Map.entry(AdminMenu.HR_DIAGNOSTICS, Set.of("SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN")),
            Map.entry(AdminMenu.GRADES, Set.of("SYSTEM_ADMIN", "HR_ADMIN")),
            Map.entry(AdminMenu.NOTICES, Set.of("SYSTEM_ADMIN", "NOTICE_ADMIN")),
            Map.entry(AdminMenu.PERSONNEL_TRANSFER, Set.of("SYSTEM_ADMIN", "HR_ADMIN")),
            Map.entry(AdminMenu.ADMIN_PERMISSIONS, Set.of("SYSTEM_ADMIN")));

    private AdminPermissionStub() {
    }

    public static AdminPermissionService legacyRoles() {
        AdminPermissionService service = mock(AdminPermissionService.class);
        when(service.can(any(), any())).thenAnswer(call -> {
            CustomUserDetails user = call.getArgument(0);
            AdminMenu menu = call.getArgument(1);
            if (user == null) {
                return false;
            }
            if (user.isSuperAdmin()) {
                return true;
            }
            return LEGACY.getOrDefault(menu, Set.of()).stream().anyMatch(user::hasRole);
        });
        return service;
    }
}
