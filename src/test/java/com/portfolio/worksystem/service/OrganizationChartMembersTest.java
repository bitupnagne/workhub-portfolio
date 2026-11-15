package com.portfolio.worksystem.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class OrganizationChartMembersTest {
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PresenceCacheService cache = mock(PresenceCacheService.class);
    private final OrganizationService service = new OrganizationService(mock(TeamMapper.class), userMapper,
            mock(EmpGradeMapper.class), mock(PasswordEncoder.class), mock(PanelRefreshPublisher.class), cache,
            mock(UserChangeLogService.class));

    @Test
    void showsEachEmployeeOnceInTheirActualOrganizationWithoutAddingChildTeamMembers() {
        Team division = team(1L, null);
        Team parent = team(2L, 1L);
        parent.setLeaderUserId(101L);
        Team child = team(3L, 2L);
        when(cache.orgTree()).thenReturn(List.of(division, parent, child, team(4L, 1L)));
        UserAccount leader = employee(101L, 2L, "김팀장");
        leader.setJobTitle("팀장");
        when(userMapper.findAllActive()).thenReturn(List.of(leader, employee(102L, 2L, "박직원"),
                employee(103L, 3L, "이하위팀원")));

        var chart = service.findOrganizationChartMembers();

        assertEquals(List.of("김팀장", "박직원"), chart.byTeam().get(2L).stream().map(m -> m.name()).toList());
        assertEquals("팀장", chart.byTeam().get(2L).getFirst().jobTitle());
        assertEquals(List.of("이하위팀원"), chart.byTeam().get(3L).stream().map(m -> m.name()).toList());
        assertTrue(chart.byTeam().get(1L).isEmpty());
        assertTrue(chart.byTeam().get(4L).isEmpty());
        assertTrue(chart.unassigned().isEmpty());
    }

    @Test
    void keepsUnassignedEmployeesVisibleButExcludesSystemAndInactiveAccounts() {
        when(cache.orgTree()).thenReturn(List.of(team(2L, null)));
        UserAccount system = employee(201L, 2L, "시스템 관리자");
        system.setSystemAccount(true);
        UserAccount retired = employee(202L, 2L, "퇴직 직원");
        retired.setActive(false);
        when(userMapper.findAllActive()).thenReturn(List.of(system, retired,
                employee(203L, null, "미소속 직원"), employee(204L, 99L, "삭제된 조직 소속 직원")));

        var chart = service.findOrganizationChartMembers();

        assertTrue(chart.byTeam().get(2L).isEmpty());
        assertEquals(List.of("미소속 직원", "삭제된 조직 소속 직원"), chart.unassigned().stream().map(m -> m.name()).toList());
    }

    @Test
    void showsUnassignedCeoInExplicitlyLedRootWithoutChangingStoredAffiliation() {
        Team root = team(1L, null);
        root.setNodeType("ROOT");
        root.setLeaderUserId(301L);
        when(cache.orgTree()).thenReturn(List.of(root, team(2L, 1L)));
        UserAccount ceo = employee(301L, null, "대표 직원");
        ceo.setJobTitle("대표이사");
        UserAccount unrelated = employee(302L, null, "다른 대표 직함 직원");
        unrelated.setJobTitle("대표이사");
        when(userMapper.findAllActive()).thenReturn(List.of(ceo, unrelated));

        var chart = service.findOrganizationChartMembers();

        assertEquals(List.of("대표 직원"), chart.byTeam().get(1L).stream().map(m -> m.name()).toList());
        assertEquals("대표이사", chart.byTeam().get(1L).getFirst().jobTitle());
        assertEquals(List.of("다른 대표 직함 직원"), chart.unassigned().stream().map(m -> m.name()).toList());
        assertNull(ceo.getTeamId());
    }

    @Test
    void preservesActualAffiliationWhenEmployeeAlsoLeadsOtherOrganizations() {
        Team root = team(1L, null);
        root.setLeaderUserId(401L);
        Team division = team(2L, 1L);
        division.setLeaderUserId(401L);
        when(cache.orgTree()).thenReturn(List.of(root, division, team(3L, 2L)));
        when(userMapper.findAllActive()).thenReturn(List.of(employee(401L, 3L, "겸임 조직장")));

        var chart = service.findOrganizationChartMembers();

        assertTrue(chart.byTeam().get(1L).isEmpty());
        assertTrue(chart.byTeam().get(2L).isEmpty());
        assertEquals(List.of("겸임 조직장"), chart.byTeam().get(3L).stream().map(m -> m.name()).toList());
        assertTrue(chart.unassigned().isEmpty());
    }

    @Test
    void showsUnassignedMultipleOrganizationLeaderOnceAndStillExcludesHiddenAccounts() {
        Team root = team(1L, null);
        root.setLeaderUserId(501L);
        Team division = team(2L, 1L);
        division.setLeaderUserId(501L);
        Team systemTeam = team(3L, 1L);
        systemTeam.setLeaderUserId(502L);
        Team inactiveTeam = team(4L, 1L);
        inactiveTeam.setLeaderUserId(503L);
        when(cache.orgTree()).thenReturn(List.of(root, division, systemTeam, inactiveTeam));
        UserAccount system = employee(502L, null, "시스템 조직장");
        system.setSystemAccount(true);
        UserAccount inactive = employee(503L, null, "퇴직 조직장");
        inactive.setActive(false);
        when(userMapper.findAllActive()).thenReturn(List.of(employee(501L, null, "겸임 조직장"), system, inactive));

        var chart = service.findOrganizationChartMembers();

        assertEquals(List.of("겸임 조직장"), chart.byTeam().get(1L).stream().map(m -> m.name()).toList());
        assertEquals(1, chart.byTeam().values().stream().mapToInt(List::size).sum());
        assertTrue(chart.unassigned().isEmpty());
    }

    @Test
    void doesNotHideStaleAffiliationByInferringMembershipFromLeadership() {
        Team root = team(1L, null);
        root.setLeaderUserId(601L);
        when(cache.orgTree()).thenReturn(List.of(root));
        when(userMapper.findAllActive()).thenReturn(List.of(employee(601L, 99L, "삭제된 조직의 조직장")));

        var chart = service.findOrganizationChartMembers();

        assertTrue(chart.byTeam().get(1L).isEmpty());
        assertEquals(List.of("삭제된 조직의 조직장"), chart.unassigned().stream().map(m -> m.name()).toList());
    }

    private static Team team(Long id, Long parentId) {
        Team team = new Team();
        team.setId(id);
        team.setParentId(parentId);
        return team;
    }

    private static UserAccount employee(Long id, Long teamId, String name) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setTeamId(teamId);
        user.setName(name);
        user.setActive(true);
        user.setSystemAccount(false);
        return user;
    }
}
