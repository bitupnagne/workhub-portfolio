package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 인사카드 열람 범위를 고정한다.
 *
 * <p>인사카드({@code /hr/members/{userId}})는 사번·연락처·입사일·휴직 기간·계약직 여부까지
 * 보여 준다. 예전에는 화면 진입 권한({@code canViewMemberHrOverview})만 확인하고
 * <b>대상자는 검사하지 않아서</b>, 팀을 하나라도 이끄는 사람이면 URL 의 userId 만 바꿔
 * 스코프 밖 직원의 카드를 열 수 있었다.
 *
 * <p>같은 화면의 휴가·출장 <b>목록</b>은 처음부터 스코프로 걸러지고 있었다 —
 * 목록만 막히고 프로필은 안 막힌 비대칭이라 의도가 아니라 누락이었다.
 */
class HrMemberScopeTest {

    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PresenceCacheService presenceCacheService = mock(PresenceCacheService.class);

    private final HrScopeService scope = new HrScopeService(userMapper, teamMapper, presenceCacheService);

    /** 팀 10 을 이끄는 팀장. 팀 10 에 부하 1명, 팀 20 에 남 1명. */
    private void orgWithTwoTeams(long leaderId) {
        Team led = team(10L, leaderId);
        Team other = team(20L, 999L);
        when(presenceCacheService.orgTree()).thenReturn(List.of(led, other));
        when(userMapper.findAllActive()).thenReturn(List.of(
                user(leaderId, 10L, "TEAM_LEADER"),
                user(11L, 10L, "EMPLOYEE"),   // 같은 팀
                user(21L, 20L, "EMPLOYEE")    // 다른 팀
        ));
        when(teamMapper.findAncestors(10L)).thenReturn(List.of(led));
        when(teamMapper.findAncestors(20L)).thenReturn(List.of(other));
    }

    @Test
    void teamLeaderCanOpenTheCardOfSomeoneInTheirScope() {
        orgWithTwoTeams(1L);
        CustomUserDetails leader = details(user(1L, 10L, "TEAM_LEADER"));

        assertThat(scope.canViewMemberHrCard(leader, 11L)).isTrue();
    }

    /*
      핵심 회귀 — 이 한 줄이 false 가 아니면 URL 의 userId 만 바꿔 남의 인사카드가 열린다.
    */
    @Test
    void teamLeaderCannotOpenTheCardOfSomeoneOutsideTheirScope() {
        orgWithTwoTeams(1L);
        CustomUserDetails leader = details(user(1L, 10L, "TEAM_LEADER"));

        assertThat(scope.canViewMemberHrCard(leader, 21L)).isFalse();
    }

    /*
      화면 진입 권한과 대상자 권한은 다르다. 진입은 되는데(스코프가 비어 있지 않으므로)
      특정 대상자는 막혀야 한다 — 예전에는 진입 권한만 보고 통과시켰다.
    */
    @Test
    void screenEntryPermissionDoesNotImplyPermissionForEveryTarget() {
        orgWithTwoTeams(1L);
        CustomUserDetails leader = details(user(1L, 10L, "TEAM_LEADER"));

        assertThat(scope.canViewMemberHrOverview(leader)).isTrue();
        assertThat(scope.canViewMemberHrCard(leader, 21L)).isFalse();
    }

    @Test
    void hrAdminCanOpenAnyCard() {
        orgWithTwoTeams(1L);
        CustomUserDetails hrAdmin = details(user(2L, 20L, "HR_ADMIN"));

        assertThat(scope.canViewMemberHrCard(hrAdmin, 11L)).isTrue();
        assertThat(scope.canViewMemberHrCard(hrAdmin, 21L)).isTrue();
    }

    @Test
    void plainEmployeeCannotOpenAnyCard() {
        orgWithTwoTeams(1L);
        CustomUserDetails employee = details(user(11L, 10L, "EMPLOYEE"));

        assertThat(scope.canViewMemberHrOverview(employee)).isFalse();
        assertThat(scope.canViewMemberHrCard(employee, 21L)).isFalse();
    }

    @Test
    void nullsAreRejectedRatherThanTreatedAsAllowed() {
        assertThat(scope.canViewMemberHrCard(null, 11L)).isFalse();
        orgWithTwoTeams(1L);
        assertThat(scope.canViewMemberHrCard(details(user(1L, 10L, "TEAM_LEADER")), null)).isFalse();
    }

    private Team team(Long id, Long leaderUserId) {
        Team team = new Team();
        team.setId(id);
        team.setLeaderUserId(leaderUserId);
        team.setNodeType("TEAM");
        return team;
    }

    private UserAccount user(Long id, Long teamId, String... roles) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setTeamId(teamId);
        user.setName("user-" + id);
        user.setActive(true);
        user.setRoles(List.of(roles));
        return user;
    }

    private CustomUserDetails details(UserAccount user) {
        return new CustomUserDetails(user);
    }
}
