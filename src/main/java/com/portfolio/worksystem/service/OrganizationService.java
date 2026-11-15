package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.TeamForm;
import com.portfolio.worksystem.dto.UserForm;
import com.portfolio.worksystem.dto.UserUpdateForm;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.OrganizationNode;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@RequiredArgsConstructor
@Service
public class OrganizationService {
    public static final String REPORT_ADMIN_TEAM_CODE = "REPORT_ADMIN_TEAM_CODE";
    private static final Set<String> ADMIN_LEVEL_ROLES = Set.of("SUPER_ADMIN", "SYSTEM_ADMIN", "REPORT_ADMIN", "HR_ADMIN", "ERP_ADMIN", "NOTICE_ADMIN");
    private static final Set<String> ORG_NODE_TYPES = Set.of("ROOT", "DIVISION", "TEAM");

    private final TeamMapper teamMapper;
    private final UserMapper userMapper;
    private final com.portfolio.worksystem.mapper.EmpGradeMapper empGradeMapper;
    private final PasswordEncoder passwordEncoder;
    private final PanelRefreshPublisher panelRefreshPublisher;
    private final PresenceCacheService presenceCacheService;
    /** [B9] 인사정보 변경이력. */
    private final UserChangeLogService userChangeLogService;

    public List<Team> findTeams() {
        return teamMapper.findAll();
    }

    public List<Team> findOrganizationNodes() {
        return presenceCacheService.orgTree();
    }

    public List<Team> weeklyReportTeams() {
        return findTeams().stream()
                .filter(t -> !Boolean.FALSE.equals(t.getWeeklyReportTarget()))
                .toList();
    }

    public List<Team> organizationNodesForManagement() {
        List<Team> nodes = presenceCacheService.orgTree();
        Team reportAdminTeam = getReportAdminTeam();
        Long reportAdminId = reportAdminTeam == null ? null : reportAdminTeam.getId();
        for (Team node : nodes) {
            boolean isReportAdmin = reportAdminId != null && reportAdminId.equals(node.getId());
            boolean hasChildren = teamMapper.countActiveChildren(node.getId()) > 0;
            boolean hasMembers = userMapper.countActiveUsersByTeamId(node.getId()) > 0;
            if (isReportAdmin) {
                node.setDeletable(false);
                node.setDeleteBlockReason("주간보고 관리 조직은 삭제할 수 없습니다.");
            } else if (hasChildren) {
                node.setDeletable(false);
                node.setDeleteBlockReason("하위 조직이 있으면 삭제할 수 없습니다.");
            } else if (hasMembers) {
                node.setDeletable(false);
                node.setDeleteBlockReason("소속 직원이 있으면 삭제할 수 없습니다.");
            } else {
                node.setDeletable(true);
                node.setDeleteBlockReason(null);
            }
        }
        return nodes;
    }

    @Transactional
    public void setWeeklyReportTarget(Long teamId, boolean target, CustomUserDetails currentUser) {
        if (!canManageWeeklyReport(currentUser) && !canManageOrganization(currentUser)) {
            throw new BusinessException("주간보고 대상 팀 설정 권한이 없습니다.");
        }
        Team team = teamMapper.findById(teamId);
        if (team == null || !Boolean.TRUE.equals(team.getActive())) {
            throw new BusinessException("조직을 찾을 수 없습니다.");
        }
        teamMapper.updateWeeklyReportTarget(teamId, target);
        presenceCacheService.evictOrgTree(); // 캐시된 조직 트리에 변경값 즉시 반영(조직수정 화면 토글 반영)
    }


    @Transactional
    public void renameTeam(Long teamId, String name, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("조직 수정 권한이 없습니다.");
        }
        Team team = teamMapper.findById(teamId);
        if (team == null || !Boolean.TRUE.equals(team.getActive())) {
            throw new BusinessException("조직을 찾을 수 없습니다.");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("조직 이름을 입력하세요.");
        }
        if (trimmed.length() > 100) {
            throw new BusinessException("조직 이름은 100자 이하로 입력하세요.");
        }
        teamMapper.updateTeamName(teamId, trimmed);
        panelRefreshPublisher.publishAllActive("organization-renamed");
    }

    public List<OrganizationNode> findOrganizationTree() {
        List<Team> teams = findOrganizationNodes();
        Map<Long, OrganizationNode> nodeById = new LinkedHashMap<>();
        teams.forEach(team -> nodeById.put(team.getId(), new OrganizationNode(team)));
        List<OrganizationNode> roots = new java.util.ArrayList<>();
        teams.forEach(team -> {
            OrganizationNode node = nodeById.get(team.getId());
            OrganizationNode parent = team.getParentId() == null ? null : nodeById.get(team.getParentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        });
        return roots;
    }

    public record OrganizationChartMember(String name, String jobTitle) {
    }

    public record OrganizationChartMembers(Map<Long, List<OrganizationChartMember>> byTeam,
                                          List<OrganizationChartMember> unassigned) {
    }

    public OrganizationChartMembers findOrganizationChartMembers() {
        Map<Long, List<OrganizationChartMember>> byTeam = new LinkedHashMap<>();
        Map<Long, Long> organizationByLeader = new LinkedHashMap<>();
        findOrganizationNodes().forEach(team -> {
            byTeam.put(team.getId(), new java.util.ArrayList<>());
            if (team.getLeaderUserId() != null) {
                organizationByLeader.putIfAbsent(team.getLeaderUserId(), team.getId());
            }
        });
        List<OrganizationChartMember> unassigned = new java.util.ArrayList<>();
        for (UserAccount user : userMapper.findAllActive()) {
            if (!Boolean.TRUE.equals(user.getActive()) || Boolean.TRUE.equals(user.getSystemAccount())) {
                continue;
            }
            OrganizationChartMember member = new OrganizationChartMember(user.getName(), user.getJobTitle());
            // 실제 소속을 우선하며, 소속이 없는 대표/조직장은 명시적으로 지정된 조직에 한 번만 표시한다.
            Long organizationId = user.getTeamId() == null ? organizationByLeader.get(user.getId()) : user.getTeamId();
            List<OrganizationChartMember> members = byTeam.get(organizationId);
            (members == null ? unassigned : members).add(member);
        }
        byTeam.replaceAll((teamId, members) -> List.copyOf(members));
        return new OrganizationChartMembers(java.util.Collections.unmodifiableMap(byTeam), List.copyOf(unassigned));
    }

    public List<UserAccount> findUsers(CustomUserDetails currentUser) {
        List<UserAccount> users = userMapper.findAllActive();
        users.forEach(user -> user.setRoles(userMapper.findRolesByUserId(user.getId())));
        return users.stream()
                .filter(user -> !Boolean.TRUE.equals(user.getSystemAccount()))
                .toList();
    }

    public List<UserAccount> findUsersForDashboard(CustomUserDetails currentUser) {
        if (currentUser == null) {
            return List.of();
        }
        if (isAdminLevel(currentUser) || canManageOrganization(currentUser) || canManageWeeklyReport(currentUser)) {
            return findUsers(currentUser);
        }
        if (currentUser.isTeamLeader()) {
            return userMapper.findByTeamId(currentUser.getTeamId());
        }
        UserAccount user = userMapper.findById(currentUser.getUserId());
        return user == null || !Boolean.TRUE.equals(user.getActive()) ? List.of() : List.of(user);
    }

    public record OrganizationIssue(String severity, String category, String target, String message, String actionUrl) {
    }

    @Transactional(readOnly = true)
    public List<OrganizationIssue> organizationIssues(CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("HR 운영 점검 권한이 없습니다.");
        }
        List<OrganizationIssue> issues = new java.util.ArrayList<>();
        for (Team node : presenceCacheService.orgTree()) {
            if (Boolean.FALSE.equals(node.getActive())) {
                continue;
            }
            boolean hasMembers = userMapper.countActiveUsersByTeamId(node.getId()) > 0;
            boolean hasChildren = teamMapper.countActiveChildren(node.getId()) > 0;
            String actionUrl = "/admin/org/" + node.getId() + "/edit";
            if (node.getLeaderUserId() == null) {
                String severity = !"TEAM".equals(node.getNodeType()) || hasMembers ? "danger" : "warn";
                String message = hasMembers || hasChildren
                        ? "조직장이 지정되지 않아 결재선과 우측 직원 목록의 역할 표시가 불안정합니다."
                        : "현재 소속 직원은 없지만 조직장이 비어 있습니다.";
                issues.add(new OrganizationIssue(severity, "조직장", node.getName(), message, actionUrl));
                continue;
            }
            UserAccount leader = userMapper.findById(node.getLeaderUserId());
            if (leader == null) {
                issues.add(new OrganizationIssue("danger", "조직장", node.getName(),
                        "지정된 조직장 계정을 찾을 수 없습니다.", actionUrl));
                continue;
            }
            if (!Boolean.TRUE.equals(leader.getActive())) {
                issues.add(new OrganizationIssue("danger", "조직장", node.getName(),
                        leader.getName() + " 계정이 비활성 상태입니다.", actionUrl));
            }
            if (Boolean.TRUE.equals(leader.getSystemAccount())) {
                issues.add(new OrganizationIssue("danger", "조직장", node.getName(),
                        leader.getName() + " 계정은 시스템 계정이라 우측 직원 목록과 일반 HR 흐름에서 숨김 처리됩니다.", actionUrl));
            }
            if (!node.getId().equals(leader.getTeamId())) {
                issues.add(new OrganizationIssue("warn", "소속", node.getName(),
                        leader.getName() + " 조직장의 소속이 '" + safeText(leader.getTeamName(), "미지정")
                                + "'로 되어 있어 조직장 표시와 결재자 판정이 어긋날 수 있습니다.", actionUrl));
            }
        }
        for (UserAccount user : userMapper.findAllActive()) {
            if (Boolean.TRUE.equals(user.getSystemAccount())) {
                continue;
            }
            if (user.getTeamId() == null) {
                issues.add(new OrganizationIssue("danger", "직원 소속", user.getName(),
                        "활성 직원인데 소속 조직이 없습니다.", "/admin/users/" + user.getId() + "/edit"));
            }
        }
        return issues;
    }

    public List<UserAccount> findAllUsersForManagement(CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("직원 관리 권한이 없습니다.");
        }
        List<UserAccount> users = userMapper.findAll();
        users.forEach(user -> user.setRoles(userMapper.findRolesByUserId(user.getId())));
        return users;
    }

    public List<UserAccount> findUsersForManagement(CustomUserDetails currentUser, String query, Long teamId, String status, String contractor) {
        String keyword = StringUtils.hasText(query) ? query.trim().toLowerCase(Locale.ROOT) : "";
        String normalizedStatus = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "all";
        String normalizedContractor = StringUtils.hasText(contractor) ? contractor.trim().toLowerCase(Locale.ROOT) : "all";
        java.util.Map<Long, String> roles = orgRoleLabels();
        List<UserAccount> users = findAllUsersForManagement(currentUser).stream()
                .filter(user -> teamId == null || teamId.equals(user.getTeamId()))
                .filter(user -> matchesStatus(user, normalizedStatus))
                .filter(user -> "all".equals(normalizedContractor)
                        || ("contractor".equals(normalizedContractor) && Boolean.TRUE.equals(user.getContractor()))
                        || ("regular".equals(normalizedContractor) && !Boolean.TRUE.equals(user.getContractor())))
                .filter(user -> matchesKeyword(user, keyword))
                .toList();
        users.forEach(u -> u.setOrgRole(roles.get(u.getId())));
        return users;
    }

    public java.util.Map<Long, String> orgRoleLabels() {
        java.util.Map<Long, java.util.List<String>> byUser = new java.util.LinkedHashMap<>();
        for (Team t : presenceCacheService.orgTree()) {
            if (t.getLeaderUserId() == null || Boolean.FALSE.equals(t.getActive())) {
                continue;
            }
            byUser.computeIfAbsent(t.getLeaderUserId(), k -> new java.util.ArrayList<>())
                    .add(orgRoleLabel(t.getNodeType()) + "(" + t.getName() + ")");
        }
        java.util.Map<Long, String> out = new java.util.LinkedHashMap<>();
        byUser.forEach((uid, labels) -> out.put(uid,
                labels.size() >= 2 ? String.join(" · ", labels) + " 겸직중" : String.join(" · ", labels)));
        return out;
    }

    private String orgRoleLabel(String nodeType) {
        if ("ROOT".equals(nodeType)) {
            return "대표이사";
        }
        if ("DIVISION".equals(nodeType)) {
            return "본부장";
        }
        if ("TEAM".equals(nodeType)) {
            return "팀장";
        }
        return "조직장";
    }

    public UserAccount findUserForManagement(Long userId, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("직원 관리 권한이 없습니다.");
        }
        UserAccount user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("직원을 찾을 수 없습니다.");
        }
        user.setRoles(userMapper.findRolesByUserId(user.getId()));
        return user;
    }

    public Team getReportAdminTeam() {
        String code = teamMapper.getSetting(REPORT_ADMIN_TEAM_CODE);
        return teamMapper.findByCode(StringUtils.hasText(code) ? code : "BIZ");
    }

    public boolean canManageWeeklyReport(CustomUserDetails currentUser) {
        return currentUser != null
                && (currentUser.isSystemAdmin() || currentUser.hasRole("REPORT_ADMIN"));
    }

    private static final java.util.List<String> MANAGEABLE_ADMIN_ROLES =
            java.util.List.of("REPORT_ADMIN", "HR_ADMIN", "ERP_ADMIN", "NOTICE_ADMIN", "SYSTEM_ADMIN", "SUPER_ADMIN");

    public List<UserAccount> adminManagedUsers() {
        List<UserAccount> users = userMapper.findAllActive();
        users.forEach(u -> u.setRoles(userMapper.findRolesByUserId(u.getId())));
        return users;
    }

    public java.util.List<String> manageableAdminRoles() {
        return MANAGEABLE_ADMIN_ROLES;
    }

    public java.util.List<String> manageableAdminRoles(CustomUserDetails currentUser) {
        if (currentUser != null && currentUser.isSuperAdmin()) {
            return MANAGEABLE_ADMIN_ROLES;
        }
        return MANAGEABLE_ADMIN_ROLES.stream()
                .filter(role -> !"SUPER_ADMIN".equals(role))
                .toList();
    }

    @Transactional
    public void setUserAdminRole(Long targetUserId, String roleCode, boolean grant, CustomUserDetails currentUser) {
        if (currentUser == null || !currentUser.isSystemAdmin()) {
            throw new BusinessException("관리자 권한 지정은 시스템/슈퍼 관리자만 가능합니다.");
        }
        if (!MANAGEABLE_ADMIN_ROLES.contains(roleCode)) {
            throw new BusinessException("지정할 수 없는 권한입니다.");
        }
        if ("SUPER_ADMIN".equals(roleCode) && !currentUser.isSuperAdmin()) {
            throw new BusinessException("슈퍼 관리자 지정 및 해제는 슈퍼 관리자만 가능합니다.");
        }
        UserAccount target = userMapper.findById(targetUserId);
        if (target == null) {
            throw new BusinessException("대상 직원을 찾을 수 없습니다.");
        }
        boolean self = currentUser.getUserId().equals(targetUserId);
        boolean targetHasSuper = userMapper.findRolesByUserId(targetUserId).contains("SUPER_ADMIN");

        if (grant) {
            if ("SUPER_ADMIN".equals(roleCode)) {
                // 슈퍼 관리자는 모든 관리자 권한을 포함 — 시스템 관리자 권한도 함께 부여(우산).
                userMapper.addRole(targetUserId, "SUPER_ADMIN");
                userMapper.addRole(targetUserId, "SYSTEM_ADMIN");
            } else {
                userMapper.addRole(targetUserId, roleCode);
            }
            return;
        }

        // 해제 — 슈퍼 지정 상태에서 하위 권한을 해제하면 슈퍼 권한이 함께 해제된다(우산 해제).
        boolean umbrellaRemoval = targetHasSuper && !"SUPER_ADMIN".equals(roleCode);
        boolean removesSuper = "SUPER_ADMIN".equals(roleCode) || umbrellaRemoval;
        if (removesSuper && !currentUser.isSuperAdmin()) {
            throw new BusinessException("슈퍼 관리자 권한 해제는 슈퍼 관리자만 가능합니다.");
        }
        if (self && (removesSuper || "SYSTEM_ADMIN".equals(roleCode))) {
            throw new BusinessException("본인의 시스템/슈퍼 관리자 권한은 해제할 수 없습니다.");
        }
        if (umbrellaRemoval) {
            userMapper.removeRole(targetUserId, "SUPER_ADMIN");
            userMapper.removeRole(targetUserId, roleCode);
        } else {
            userMapper.removeRole(targetUserId, roleCode);
        }
    }

    public boolean canManageOrganization(CustomUserDetails currentUser) {
        return currentUser != null
                && (currentUser.hasRole("SYSTEM_ADMIN") || currentUser.hasRole("HR_ADMIN") || currentUser.hasRole("ERP_ADMIN"));
    }

    /** 공지 관리 권한 — 전용 역할(NOTICE_ADMIN) 또는 시스템/슈퍼 관리자. */
    public boolean canManageNotice(CustomUserDetails currentUser) {
        return currentUser != null
                && (currentUser.isSystemAdmin() || currentUser.hasRole("NOTICE_ADMIN"));
    }

    @Transactional
    public void setReportAdminTeam(String teamCode, CustomUserDetails currentUser) {
        if (currentUser == null || !currentUser.isSystemAdmin()) {
            throw new BusinessException("시스템 관리자만 주간보고 관리자 팀을 지정할 수 있습니다.");
        }
        Team team = teamMapper.findByCode(teamCode);
        if (team == null || !team.isAssignableTeam()) {
            throw new BusinessException("주간보고 관리자에는 실제 팀만 지정할 수 있습니다.");
        }
        teamMapper.setSetting(REPORT_ADMIN_TEAM_CODE, teamCode);
    }

    @Transactional
    public void createTeam(TeamForm form, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("조직 관리 권한이 없습니다.");
        }
        String code = form.code().trim().toUpperCase();
        if (teamMapper.findByCode(code) != null) {
            throw new BusinessException("이미 사용 중인 조직 코드입니다.");
        }
        String nodeType = normalizeNodeType(form.nodeType());
        Team parent = null;
        if (form.parentId() != null) {
            parent = teamMapper.findById(form.parentId());
            if (parent == null || !Boolean.TRUE.equals(parent.getActive())) {
                throw new BusinessException("상위 조직을 찾을 수 없습니다.");
            }
        }
        if ("ROOT".equals(nodeType) && parent != null) {
            throw new BusinessException("최상위 조직은 상위 조직을 지정할 수 없습니다.");
        }
        Team team = new Team();
        team.setCode(code);
        team.setName(form.name().trim());
        team.setParentId(parent == null ? null : parent.getId());
        team.setNodeType(nodeType);
        teamMapper.insertTeam(team);
        panelRefreshPublisher.publishAllActive("organization-created");
    }

    @Transactional
    public void createUser(UserForm form, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("직원 등록 권한이 없습니다.");
        }
        Team team = requireAssignableTeam(form.teamId());
        String username = form.username().trim();
        if (userMapper.findByUsername(username) != null) {
            throw new BusinessException("이미 사용 중인 로그인 ID입니다.");
        }
        String employeeNumber = normalizeEmployeeNumber(form.employeeNumber(), null);
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEmployeeNumber(employeeNumber);
        user.setPassword(passwordEncoder.encode(requiredInitialPassword()));
        applyUserFields(user, form.name(), team.getId(), form.dept(), form.extensionNumber(), form.mobileNumber(), form.jobTitle(), form.email(), form.hireDate(), true);
        user.setGradeId(resolveGradeId(form.gradeId()));
        userMapper.insertUser(user);
        userMapper.addRole(user.getId(), "EMPLOYEE");
        if (form.leader()) {
            assignOrganizationLeader(team.getId(), user.getId(), currentUser);
        }
        panelRefreshPublisher.publishAllActive("user-created");
    }

    /** An operator-supplied initial password is required; this source contains no shared credential. */
    @org.springframework.beans.factory.annotation.Value("${app.accounts.initial-password:${WORKHUB_INITIAL_PASSWORD:}}")
    private String initialPassword;

    private String requiredInitialPassword() {
        if (initialPassword == null || initialPassword.isBlank() || initialPassword.length() < 12) {
            throw new BusinessException("초기 비밀번호를 외부 설정으로 12자 이상 제공해 주세요.");
        }
        return initialPassword;
    }

    /** [UX35] 비밀번호 초기화 — 초기 비밀번호로 재설정하고 다음 로그인 시 변경을 강제한다. */
    @Transactional
    public void resetPassword(Long userId, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("비밀번호 초기화 권한이 없습니다.");
        }
        if (userId.equals(currentUser.getUserId())) {
            throw new BusinessException("본인 비밀번호는 마이페이지에서 변경하세요.");
        }
        UserAccount user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("직원을 찾을 수 없습니다.");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(requiredInitialPassword()));
        userMapper.updateMustChangePassword(userId, true);
    }

    /** [보안] 관리자에 의한 대상 사용자 OTP 강제 초기화 — 분실 대응. 대상자는 다음 로그인 시 재등록. */
    @Transactional
    public void resetOtp(Long userId, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("OTP 초기화 권한이 없습니다.");
        }
        UserAccount user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("직원을 찾을 수 없습니다.");
        }
        userMapper.updateOtp(userId, null, false);
    }

    @Transactional
    public void updateUser(UserUpdateForm form, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("직원 수정 권한이 없습니다.");
        }
        UserAccount user = userMapper.findById(form.id());
        if (user == null) {
            throw new BusinessException("직원을 찾을 수 없습니다.");
        }
        // [B9] 수정 전 모습을 떠 둔다 — 아래에서 같은 객체를 고치므로 지금 복사해야 비교가 된다.
        UserAccount before = UserChangeLogService.snapshot(user);
        Long effectiveTeamId = form.teamId() == null ? null : requireAssignableTeam(form.teamId()).getId();
        clearTeamLeaderAssignmentIfMoved(user.getId(), effectiveTeamId);
        user.setEmployeeNumber(normalizeEmployeeNumber(form.employeeNumber(), user.getId()));
        applyUserFields(user, form.name(), effectiveTeamId, form.dept(), form.extensionNumber(), form.mobileNumber(), form.jobTitle(), form.email(), form.hireDate(), form.active());
        // [B6] 휴직 기간 — 종료일만 입력 불가, 종료일은 시작일 이후. 복직 = 기간 비우기
        if (form.loaStart() == null && form.loaEnd() != null) {
            throw new BusinessException("휴직 종료일만 입력할 수 없습니다. 휴직 시작일을 함께 입력하세요.");
        }
        if (form.loaStart() != null && form.loaEnd() != null && form.loaEnd().isBefore(form.loaStart())) {
            throw new BusinessException("휴직 종료일은 시작일보다 빠를 수 없습니다.");
        }
        user.setLoaStart(form.loaStart());
        user.setLoaEnd(form.loaEnd());
        user.setGradeId(resolveGradeId(form.gradeId()));
        user.setContractor(form.contractor());
        userMapper.updateUser(user);
        // [B9] 팀·직급은 id 로 바뀌므로 <이름>을 다시 읽어 이력에 남긴다.
        userChangeLogService.record(before, userMapper.findById(user.getId()), "ADMIN", currentUser);
        panelRefreshPublisher.publishAllActive("user-updated");
    }

    @Transactional(readOnly = true)
    public List<UserAccount> orderableMembers(Long nodeId, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("조직 관리 권한이 없습니다.");
        }
        return userMapper.findOrderableMembers(nodeId);
    }

    @Transactional
    public void saveMemberOrder(Long nodeId, List<Long> orderedUserIds, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("조직 관리 권한이 없습니다.");
        }
        if (orderedUserIds == null) {
            return;
        }
        java.util.Set<Long> valid = userMapper.findOrderableMembers(nodeId).stream()
                .map(UserAccount::getId)
                .collect(java.util.stream.Collectors.toSet());
        int order = 1;
        for (Long uid : orderedUserIds) {
            if (uid != null && valid.contains(uid)) {
                userMapper.updateMemberOrder(uid, order++);
            }
        }
        panelRefreshPublisher.publishAllActive("member-order");
    }

    @Transactional
    public void removeMemberFromOrganization(Long nodeId, Long userId, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("조직 관리 권한이 없습니다.");
        }
        Team node = teamMapper.findById(nodeId);
        if (node == null || !Boolean.TRUE.equals(node.getActive())) {
            throw new BusinessException("조직을 찾을 수 없습니다.");
        }
        UserAccount user = userMapper.findById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getActive()) || Boolean.TRUE.equals(user.getSystemAccount())) {
            throw new BusinessException("해제할 직원을 찾을 수 없습니다.");
        }
        if (!node.getId().equals(user.getTeamId())) {
            throw new BusinessException("현재 조직 소속 직원만 해제할 수 있습니다.");
        }
        if (user.getId().equals(node.getLeaderUserId())) {
            throw new BusinessException("현재 조직장은 조직원 목록에서 해제할 수 없습니다. 먼저 조직장 지정을 해제하세요.");
        }
        userMapper.updateUserTeam(user.getId(), null);
        userMapper.updateMemberOrder(user.getId(), 0);
        panelRefreshPublisher.publishAllActive("member-removed");
    }

    @Transactional
    public void deleteUser(Long userId, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("직원 삭제 권한이 없습니다.");
        }
        if (currentUser.getUserId().equals(userId)) {
            throw new BusinessException("본인 계정은 삭제할 수 없습니다.");
        }
        UserAccount user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException("직원을 찾을 수 없습니다.");
        }
        user.setRoles(userMapper.findRolesByUserId(user.getId()));
        if (isAdminLevel(user) && !currentUser.isSystemAdmin()) {
            throw new BusinessException("관리자 권한 계정 삭제는 시스템 관리자만 가능합니다.");
        }
        teamMapper.clearLeaderByUserId(userId);
        userMapper.removeRole(userId, "TEAM_LEADER");
        userMapper.deactivateUser(userId);
        panelRefreshPublisher.publishAllActive("user-deleted");
    }

    @Transactional
    public void deleteTeam(Long teamId, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("조직 삭제 권한이 없습니다.");
        }
        Team team = teamMapper.findById(teamId);
        if (team == null || !Boolean.TRUE.equals(team.getActive())) {
            throw new BusinessException("조직을 찾을 수 없습니다.");
        }
        Team reportAdminTeam = getReportAdminTeam();
        if (reportAdminTeam != null && reportAdminTeam.getId().equals(team.getId())) {
            throw new BusinessException("주간보고 관리자 조직은 삭제할 수 없습니다.");
        }
        if (teamMapper.countActiveChildren(teamId) > 0) {
            throw new BusinessException("하위 조직이 있는 조직은 삭제할 수 없습니다.");
        }
        if (userMapper.countActiveUsersByTeamId(teamId) > 0) {
            throw new BusinessException("소속 직원이 있는 조직은 삭제할 수 없습니다.");
        }
        if (team.isAssignableTeam() && team.getLeaderUserId() != null) {
            userMapper.removeRole(team.getLeaderUserId(), "TEAM_LEADER");
        }
        teamMapper.deactivateTeam(teamId);
        panelRefreshPublisher.publishAllActive("organization-deleted");
    }

    @Transactional
    public void assignOrganizationLeader(Long teamId, Long leaderUserId, CustomUserDetails currentUser) {
        if (!canManageOrganization(currentUser)) {
            throw new BusinessException("조직장 지정 권한이 없습니다.");
        }
        Team team = teamMapper.findById(teamId);
        if (team == null || !Boolean.TRUE.equals(team.getActive())) {
            throw new BusinessException("조직을 찾을 수 없습니다.");
        }
        Long normalizedLeaderId = leaderUserId;
        if (normalizedLeaderId != null) {
            UserAccount leader = userMapper.findById(normalizedLeaderId);
            if (leader == null || !Boolean.TRUE.equals(leader.getActive())) {
                throw new BusinessException("지정할 직원을 찾을 수 없습니다.");
            }
            if (team.isAssignableTeam() && !team.getId().equals(leader.getTeamId())) {
                throw new BusinessException("팀장은 해당 팀 소속 직원 중에서 지정하세요.");
            }
        }
        teamMapper.updateTeamLeader(team.getId(), normalizedLeaderId);
        if (team.isAssignableTeam()) {
            teamMapper.updateTeamLeaderRole(team.getId(), normalizedLeaderId);
            if (normalizedLeaderId != null) {
                userMapper.addRole(normalizedLeaderId, "TEAM_LEADER");
                userMapper.addRole(normalizedLeaderId, "EMPLOYEE");
            }
        } else if (normalizedLeaderId != null) {
            // 본부/대표 조직장의 소속 = 자신이 이끄는 그 조직(정합 유지)
            userMapper.updateUserTeam(normalizedLeaderId, team.getId());
        }
        panelRefreshPublisher.publishAllActive("organization-leader");
    }

    private void applyUserFields(UserAccount user, String name, Long teamId, String dept,
                                 String extensionNumber, String mobileNumber, String jobTitle,
                                 String email, java.time.LocalDate hireDate, boolean active) {
        user.setName(requiredTrim(name, "이름을 입력하세요."));
        user.setTeamId(teamId);
        user.setDept(requiredTrim(dept, "부서/직무를 입력하세요."));
        user.setExtensionNumber(trimToNull(extensionNumber));
        user.setMobileNumber(trimToNull(mobileNumber));
        user.setJobTitle(trimToNull(jobTitle));
        user.setEmail(trimToNull(email));
        user.setHireDate(hireDate);
        user.setActive(active);
    }

    /** 등급 id 검증: null 허용(미지정), 존재하지 않으면 예외. 직원 직급은 관리자만 지정한다. */
    private Long resolveGradeId(Long gradeId) {
        if (gradeId == null) {
            return null;
        }
        if (empGradeMapper.findById(gradeId) == null) {
            throw new BusinessException("존재하지 않는 등급입니다.");
        }
        return gradeId;
    }

    private String normalizeEmployeeNumber(String value, Long currentUserId) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        UserAccount existing = userMapper.findByEmployeeNumber(normalized);
        if (existing != null && !existing.getId().equals(currentUserId)) {
            throw new BusinessException("이미 사용 중인 사번입니다.");
        }
        return normalized;
    }

    private Team requireAssignableTeam(Long teamId) {
        Team team = teamMapper.findById(teamId);
        if (team == null || !team.isMembershipAssignable()) {
            throw new BusinessException("직원 소속은 팀 또는 본부만 지정할 수 있습니다.");
        }
        return team;
    }

    private void clearTeamLeaderAssignmentIfMoved(Long userId, Long targetTeamId) {
        boolean cleared = false;
        for (Team team : findOrganizationNodes()) {
            if (team.isAssignableTeam()
                    && userId.equals(team.getLeaderUserId())
                    && !team.getId().equals(targetTeamId)) {
                teamMapper.updateTeamLeader(team.getId(), null);
                cleared = true;
            }
        }
        if (cleared) {
            boolean stillTeamLeader = findOrganizationNodes().stream()
                    .anyMatch(team -> team.isAssignableTeam() && userId.equals(team.getLeaderUserId()));
            if (!stillTeamLeader) {
                userMapper.removeRole(userId, "TEAM_LEADER");
            }
        }
    }

    public List<Team> findMembershipTeams() {
        return presenceCacheService.orgTree().stream()
                .filter(t -> !Boolean.FALSE.equals(t.getActive()))
                .filter(Team::isMembershipAssignable)
                .toList();
    }

    private String normalizeNodeType(String nodeType) {
        String normalized = StringUtils.hasText(nodeType) ? nodeType.trim().toUpperCase() : "TEAM";
        if (!ORG_NODE_TYPES.contains(normalized)) {
            throw new BusinessException("조직 유형이 올바르지 않습니다.");
        }
        return normalized;
    }

    private boolean isAdminLevel(CustomUserDetails currentUser) {
        return currentUser != null && ADMIN_LEVEL_ROLES.stream().anyMatch(currentUser::hasRole);
    }

    private boolean isAdminLevel(UserAccount user) {
        return user != null && user.getRoles() != null && user.getRoles().stream().anyMatch(ADMIN_LEVEL_ROLES::contains);
    }

    private boolean matchesStatus(UserAccount user, String status) {
        if ("active".equals(status)) {
            return Boolean.TRUE.equals(user.getActive());
        }
        if ("inactive".equals(status)) {
            return !Boolean.TRUE.equals(user.getActive());
        }
        return true;
    }

    private boolean matchesKeyword(UserAccount user, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        return contains(user.getName(), keyword)
                || contains(user.getUsername(), keyword)
                || contains(user.getEmployeeNumber(), keyword)
                || contains(user.getTeamName(), keyword)
                || contains(user.getDept(), keyword)
                || contains(user.getJobTitle(), keyword)
                || contains(user.getEmail(), keyword)
                || contains(user.getMobileNumber(), keyword)
                || contains(user.getExtensionNumber(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String requiredTrim(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
