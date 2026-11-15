package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>누가 누구의 인사 정보를 볼 수 있는가</b> — 인사 기능 전체가 딛고 서는 권한 범위.
 *
 * <p>휴가·근태·출장·결재문서가 모두 같은 질문을 한다. 그래서 이 판정이 어느 한 화면의
 * 사정에 맞춰 조금씩 달라지면 <b>화면마다 보이는 사람이 달라진다</b> — 목록은 막혔는데
 * 상세는 열리는 식의 비대칭이 실제로 있었다({@link #canViewMemberHrCard} 주석 참고).
 * 한 곳에 모아 두는 이유가 그것이다.
 *
 * <p>범위는 세 단계다.
 * <ol>
 *   <li>인사담당자·시스템관리자 — 전 직원(시스템 계정 제외)</li>
 *   <li>본부장·팀장 — 자기가 이끄는 노드의 <b>모든 자손</b> 소속 직원</li>
 *   <li>일반 직원 — 본인만</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class HrScopeService {

    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final PresenceCacheService presenceCacheService;

    public boolean canManageHr(CustomUserDetails currentUser) {
        return currentUser != null && (currentUser.isSystemAdmin() || currentUser.hasRole("HR_ADMIN"));
    }

    public boolean canReviewPeople(CustomUserDetails currentUser) {
        return currentUser != null && (canManageHr(currentUser) || currentUser.isTeamLeader());
    }

    /**
     * 인사카드 화면에 <b>들어올 수 있는가</b>. 대상자별 판정이 아니다.
     *
     * <p>대상자를 지정해 여는 화면(`/hr/members/{userId}`)에서는 이것만으로 부족하다 —
     * 반드시 {@link #canViewMemberHrCard(CustomUserDetails, Long)} 로 한 번 더 좁혀야 한다.
     */
    public boolean canViewMemberHrOverview(CustomUserDetails currentUser) {
        if (currentUser == null) return false;
        if (currentUser.isSuperAdmin() || currentUser.isSystemAdmin()) return true;
        return !memberScopeUserIds(currentUser).isEmpty();
    }

    /**
     * <b>이 대상자의</b> 인사카드를 열람할 수 있는가.
     *
     * <p>예전에는 화면 진입 여부({@link #canViewMemberHrOverview})만 보고 대상자는 검사하지
     * 않았다. 그래서 팀을 하나라도 이끄는 사람이면 URL 의 userId 만 바꿔 <b>스코프 밖 직원의
     * 인사카드</b>(사번·연락처·입사일·휴직 기간·계약직 여부 등)를 볼 수 있었다.
     *
     * <p>같은 화면의 휴가·출장·근태 <b>목록</b>은 처음부터 {@code memberScopeUserIds} 로
     * 걸러지고 있었다. 목록만 막고 대상자 프로필은 안 막힌 비대칭이었다.
     */
    public boolean canViewMemberHrCard(CustomUserDetails currentUser, Long targetUserId) {
        if (currentUser == null || targetUserId == null) return false;
        return memberScopeUserIds(currentUser).contains(targetUserId);
    }

    public String memberHrScopeLabel(CustomUserDetails currentUser) {
        if (canManageHr(currentUser)) {
            return "직원";
        }
        boolean leadsDivision = ledNodes(currentUser).stream()
                .anyMatch(team -> "DIVISION".equals(team.getNodeType()));
        return leadsDivision ? "본부원" : "팀원";
    }

    /** 이 사람이 인사 정보를 볼 수 있는 <b>대상자 집합</b>. */
    public Set<Long> memberScopeUserIds(CustomUserDetails currentUser) {
        if (currentUser == null) {
            return Set.of();
        }
        if (canManageHr(currentUser) || currentUser.isSuperAdmin()) {
            // 본인 포함 — 직원 HR 관리 화면에서 본인 데이터도 함께 조회할 수 있도록 한다.
            return userMapper.findAllActive().stream()
                    .filter(user -> !Boolean.TRUE.equals(user.getSystemAccount()))
                    .map(UserAccount::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        Set<Long> ledNodeIds = ledNodes(currentUser).stream()
                .map(Team::getId)
                .collect(Collectors.toSet());
        if (ledNodeIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (UserAccount user : userMapper.findAllActive()) {
            if (Boolean.TRUE.equals(user.getSystemAccount())
                    || user.getTeamId() == null) {
                continue;
            }
            boolean inScope = teamMapper.findAncestors(user.getTeamId()).stream()
                    .anyMatch(team -> ledNodeIds.contains(team.getId()));
            if (inScope) {
                result.add(user.getId());
            }
        }
        return result;
    }

    /** 이 사람이 리더로 지정된 조직 노드들. */
    public List<Team> ledNodes(CustomUserDetails currentUser) {
        if (currentUser == null) {
            return List.of();
        }
        return presenceCacheService.orgTree().stream()
                .filter(team -> currentUser.getUserId().equals(team.getLeaderUserId()))
                .toList();
    }

    /** [Q-3] 본부장(DIVISION 노드 리더) 여부. */
    @Transactional(readOnly = true)
    public boolean isDivisionLeader(CustomUserDetails currentUser) {
        return ledNodes(currentUser).stream()
                .anyMatch(t -> "DIVISION".equals(t.getNodeType()));
    }

    /** [Q-3] 본부장이 보는 산하 팀 목록(자기 본부 + 자손 TEAM 노드). canManageWeeklyReport=true면 전체. */
    @Transactional(readOnly = true)
    public List<Team> reportVisibleTeams(CustomUserDetails currentUser) {
        Set<Long> ledDivisionIds = ledNodes(currentUser).stream()
                .filter(t -> "DIVISION".equals(t.getNodeType()))
                .map(Team::getId)
                .collect(Collectors.toSet());
        if (ledDivisionIds.isEmpty()) return List.of();
        // 본부 + 본부의 자손 노드들을 모두 수집
        LinkedHashSet<Long> visibleTeamIds = new LinkedHashSet<>();
        for (Team node : presenceCacheService.orgTree()) {
            if (ledDivisionIds.contains(node.getId())) {
                visibleTeamIds.add(node.getId());
                continue;
            }
            // ancestors에 본부가 있으면 산하
            boolean underDivision = teamMapper.findAncestors(node.getId()).stream()
                    .anyMatch(anc -> ledDivisionIds.contains(anc.getId()));
            if (underDivision) visibleTeamIds.add(node.getId());
        }
        return presenceCacheService.orgTree().stream()
                .filter(t -> visibleTeamIds.contains(t.getId()))
                .toList();
    }

    /**
     * [P-6] 주간보고 검색 범위 — 본부장/팀장이면 산하 모든 직원 + 본인.
     * 일반 직원이면 본인만. 슈퍼관리자는 호출자가 별도로 처리하므로 여기서는 동일 규칙.
     */
    @Transactional(readOnly = true)
    public Set<Long> reportSearchScope(CustomUserDetails currentUser) {
        if (currentUser == null) return Set.of();
        var scope = new LinkedHashSet<Long>(memberScopeUserIds(currentUser));
        scope.add(currentUser.getUserId());
        return scope;
    }

    /** 인사 목록 화면에 뿌릴 사용자 — 인사담당자는 전원, 그 외는 본인만. */
    public List<UserAccount> visibleUsers(CustomUserDetails currentUser) {
        if (canManageHr(currentUser)) {
            return userMapper.findAllActive();
        }
        UserAccount user = userMapper.findById(currentUser.getUserId());
        return user == null ? List.of() : List.of(user);
    }

    /** 목록 조회 필터 — 인사담당자는 제한 없음(null), 그 외는 본인 것만. */
    public Visibility visibility(CustomUserDetails currentUser) {
        if (canManageHr(currentUser)) {
            return new Visibility(null, null);
        }
        return new Visibility(currentUser.getUserId(), null);
    }

    public record Visibility(Long userId, Long teamId) {
    }
}
