package com.portfolio.worksystem.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** [PERM] 주소 → 관리자 메뉴 판정. 가장 긴 접두어가 이기고, '=' 은 그 주소 하나만이다. */
class AdminMenuTest {

    @Test
    void longestPrefixWins() {
        assertThat(AdminMenu.forPath("/hr/overtime/settings/groups")).contains(AdminMenu.OVERTIME_SETTINGS);
        assertThat(AdminMenu.forPath("/admin/personnel/transfer/1/delete")).contains(AdminMenu.PERSONNEL_TRANSFER);
        assertThat(AdminMenu.forPath("/admin/grades/3/update")).contains(AdminMenu.GRADES);
        assertThat(AdminMenu.forPath("/admin/system/external-menu")).contains(AdminMenu.SYSTEM);
    }

    @Test
    void exactPathDoesNotSwallowChildren() {
        assertThat(AdminMenu.forPath("/people")).contains(AdminMenu.USERS);
        // 본인 인사카드·직원 상세는 관리자 메뉴가 아니다
        assertThat(AdminMenu.forPath("/people/me")).isEmpty();
        assertThat(AdminMenu.forPath("/people/12")).isEmpty();
    }

    @Test
    void prefixMatchesWholeSegmentsOnly() {
        assertThat(AdminMenu.forPath("/admin/org")).contains(AdminMenu.ORG_CHART);
        assertThat(AdminMenu.forPath("/admin/organization")).isEmpty();
        assertThat(AdminMenu.forPath("/hr/leave/ledger/csv")).contains(AdminMenu.LEAVE_LEDGER);
        assertThat(AdminMenu.forPath("/hr/leave/new")).isEmpty();
        assertThat(AdminMenu.forPath("/hr/leave-types/save-all")).contains(AdminMenu.ATTENDANCE_POLICY);
        assertThat(AdminMenu.forPath("/hr/overtime")).isEmpty();
        assertThat(AdminMenu.forPath("/hr/overtime/settings/groups")).contains(AdminMenu.OVERTIME_SETTINGS);
    }

    @Test
    void grantRules() {
        assertThat(AdminMenu.ADMIN_PERMISSIONS.isSuperOnlyGrant()).isTrue();
        assertThat(AdminMenu.SYSTEM.isGrantable()).isFalse();
        assertThat(AdminMenu.PAYROLL.isGrantable()).isTrue();
    }
}
