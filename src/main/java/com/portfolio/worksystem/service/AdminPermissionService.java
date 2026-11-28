package com.portfolio.worksystem.service;

import com.portfolio.worksystem.config.ReleaseFeatures;
import com.portfolio.worksystem.mapper.AdminMenuGrantMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.AdminMenu;
import com.portfolio.worksystem.model.AdminMenuGrant;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.AdminGrantLookup;
import com.portfolio.worksystem.security.CustomUserDetails;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PERM] 관리자 메뉴별 권한 — 누가 어떤 관리자 메뉴를 쓸 수 있는지.
 *
 * <ul>
 *   <li>슈퍼관리자는 모든 메뉴를 가진다(행이 없어도).</li>
 *   <li>그 밖의 사람은 본인에게 준 것 + 지금 조직장인 조직의 자리에 준 것.</li>
 *   <li>권한을 주고 빼는 일은 슈퍼관리자와 '관리자 권한 관리' 권한자가 한다. 단 '관리자 권한 관리' 자체는
 *       슈퍼관리자만 준다 — 나눠 주는 권한이 계속 번지지 않게.</li>
 * </ul>
 * 조직장이 바뀌어도 반영되도록 짧게(30초)만 캐시하고, 주고 뺄 때는 바로 비운다.
 */
@Service
@RequiredArgsConstructor
public class AdminPermissionService {
    private static final long CACHE_MILLIS = 30_000L;

    private final AdminMenuGrantMapper grantMapper;
    private final TeamMapper teamMapper;
    private final UserMapper userMapper;
    private final ReleaseFeatures releaseFeatures;

    private record Cached(Set<AdminMenu> menus, long at) {
    }

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void registerLookup() {
        AdminGrantLookup.register(userId -> !grantedMenus(userId).isEmpty());
    }

    /** 이 판에서 쓸 수 있는 관리자 메뉴(화면 순서). */
    public List<AdminMenu> availableMenus() {
        return AdminMenu.ordered().stream().filter(menu -> menu.isAvailable(releaseFeatures)).toList();
    }

    /** 이 사람이 쓸 수 있는 메뉴. */
    public Set<AdminMenu> menusFor(CustomUserDetails user) {
        if (user == null) {
            return Set.of();
        }
        EnumSet<AdminMenu> result = EnumSet.noneOf(AdminMenu.class);
        if (user.isSuperAdmin()) {
            result.addAll(availableMenus());
            return result;
        }
        for (AdminMenu menu : grantedMenus(user.getUserId())) {
            if (menu.isGrantable() && menu.isAvailable(releaseFeatures)) {
                result.add(menu);
            }
        }
        return result;
    }

    public boolean can(CustomUserDetails user, AdminMenu menu) {
        return menusFor(user).contains(menu);
    }

    /** 사이드바·관리자 진입점용 메뉴 키. */
    public Set<String> menuKeysFor(CustomUserDetails user) {
        Set<String> keys = new java.util.HashSet<>();
        menusFor(user).forEach(menu -> keys.add(menu.getKey()));
        return keys;
    }

    /** '관리자 메뉴' 버튼이 처음 여는 화면 — 쓸 수 있는 첫 메뉴. */
    public String entryUrl(CustomUserDetails user) {
        Set<AdminMenu> menus = menusFor(user);
        return availableMenus().stream().filter(menus::contains).map(AdminMenu::getUrl).findFirst().orElse(null);
    }

    public boolean canManagePermissions(CustomUserDetails user) {
        return user != null && (user.isSuperAdmin() || can(user, AdminMenu.ADMIN_PERMISSIONS));
    }

    /** 이 사람이 그 메뉴 권한을 주고 뺄 수 있는지. */
    public boolean canGrant(CustomUserDetails user, AdminMenu menu) {
        if (user == null || !menu.isGrantable() || !menu.isAvailable(releaseFeatures)) {
            return false;
        }
        if (user.isSuperAdmin()) {
            return true;
        }
        return !menu.isSuperOnlyGrant() && can(user, AdminMenu.ADMIN_PERMISSIONS);
    }

    /** 메뉴별 현재 권한 보유(사람·자리). */
    @Transactional(readOnly = true)
    public Map<AdminMenu, List<AdminMenuGrant>> grantsByMenu() {
        Map<AdminMenu, List<AdminMenuGrant>> result = new LinkedHashMap<>();
        availableMenus().forEach(menu -> result.put(menu, new ArrayList<>()));
        for (AdminMenuGrant grant : grantMapper.findAll()) {
            AdminMenu.of(grant.getMenuKey()).map(result::get).ifPresent(list -> list.add(grant));
        }
        return result;
    }

    /** 권한을 줄 수 있는 조직장 자리 — 활성 조직 전부(공석 포함), 이름순. */
    @Transactional(readOnly = true)
    public List<Team> leaderSeats() {
        List<Team> teams = new ArrayList<>(teamMapper.findAllNodes().stream()
                .filter(team -> !Boolean.FALSE.equals(team.getActive()))
                .toList());
        teams.sort((a, b) -> String.valueOf(a.getName()).compareTo(String.valueOf(b.getName())));
        return teams;
    }

    /** 권한을 줄 수 있는 사람 — 재직자, 이름순. */
    @Transactional(readOnly = true)
    public List<UserAccount> candidates() {
        List<UserAccount> users = new ArrayList<>(userMapper.findAllActive().stream()
                .filter(user -> !Boolean.TRUE.equals(user.getSystemAccount()))
                .toList());
        users.sort((a, b) -> String.valueOf(a.getName()).compareTo(String.valueOf(b.getName())));
        return users;
    }

    @Transactional
    public AdminMenuGrant grant(CustomUserDetails actor, String menuKey, String granteeType, Long targetId) {
        AdminMenu menu = AdminMenu.of(menuKey).orElseThrow(() -> new BusinessException("없는 메뉴입니다."));
        if (!canGrant(actor, menu)) {
            throw new BusinessException(menu.isSuperOnlyGrant()
                    ? "'" + menu.getLabel() + "' 권한은 슈퍼관리자만 줄 수 있습니다."
                    : "이 메뉴 권한을 줄 수 없습니다.");
        }
        if (targetId == null) {
            throw new BusinessException("권한을 줄 대상을 고르세요.");
        }
        AdminMenuGrant grant = new AdminMenuGrant();
        grant.setMenuKey(menu.getKey());
        grant.setGrantedBy(actor.getUserId());
        if (AdminMenuGrant.TEAM_LEADER.equals(granteeType)) {
            Team team = teamMapper.findAllNodes().stream().filter(t -> targetId.equals(t.getId())).findFirst()
                    .orElseThrow(() -> new BusinessException("없는 조직입니다."));
            grant.setGranteeType(AdminMenuGrant.TEAM_LEADER);
            grant.setTeamId(team.getId());
        } else {
            UserAccount target = userMapper.findById(targetId);
            if (target == null || Boolean.FALSE.equals(target.getActive())) {
                throw new BusinessException("재직 중인 직원이 아닙니다.");
            }
            grant.setGranteeType(AdminMenuGrant.USER);
            grant.setUserId(target.getId());
        }
        if (grantMapper.insert(grant) == 0) {
            throw new BusinessException("이미 이 권한을 가지고 있습니다.");
        }
        AdminMenuGrant saved = grantMapper.findById(grant.getId());
        grantMapper.insertLog(menu.getKey(), "GRANT", saved.getGranteeType(), saved.getUserId(), saved.getTeamId(),
                saved.getLabel(), actor.getUserId(), actor.getName());
        cache.clear();
        return saved;
    }

    @Transactional
    public AdminMenuGrant revoke(CustomUserDetails actor, Long grantId) {
        AdminMenuGrant grant = grantMapper.findById(grantId);
        if (grant == null) {
            throw new BusinessException("이미 회수된 권한입니다.");
        }
        AdminMenu menu = AdminMenu.of(grant.getMenuKey()).orElseThrow(() -> new BusinessException("없는 메뉴입니다."));
        if (!canGrant(actor, menu)) {
            throw new BusinessException("이 메뉴 권한을 뺄 수 없습니다.");
        }
        // 자기 손으로 '관리자 권한 관리'를 빼 이 화면에서 스스로 잠기는 일을 막는다(슈퍼관리자는 해당 없음).
        if (menu == AdminMenu.ADMIN_PERMISSIONS && !actor.isSuperAdmin()) {
            throw new BusinessException("'관리자 권한 관리' 권한은 슈퍼관리자만 뺄 수 있습니다.");
        }
        grantMapper.delete(grantId);
        grantMapper.insertLog(menu.getKey(), "REVOKE", grant.getGranteeType(), grant.getUserId(), grant.getTeamId(),
                grant.getLabel(), actor.getUserId(), actor.getName());
        cache.clear();
        return grant;
    }

    public void evictAll() {
        cache.clear();
    }

    private Set<AdminMenu> grantedMenus(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(userId);
        if (cached != null && now - cached.at() < CACHE_MILLIS) {
            return cached.menus();
        }
        EnumSet<AdminMenu> menus = EnumSet.noneOf(AdminMenu.class);
        for (String key : grantMapper.findMenuKeysForUser(userId)) {
            AdminMenu.of(key).ifPresent(menus::add);
        }
        Set<AdminMenu> frozen = Collections.unmodifiableSet(menus);
        cache.put(userId, new Cached(frozen, now));
        return frozen;
    }
}
