package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.ApprovalDelegationForm;
import com.portfolio.worksystem.dto.ApprovalRouteChainForm;
import com.portfolio.worksystem.mapper.ApprovalMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.ApprovalDelegation;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.ApprovalInboxItem;
import com.portfolio.worksystem.model.ApprovalRouteRule;
import com.portfolio.worksystem.model.ApprovalStatus;
import com.portfolio.worksystem.model.ApprovalStep;
import com.portfolio.worksystem.model.ApproverType;
import com.portfolio.worksystem.model.RequesterLevel;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@RequiredArgsConstructor
@Service
public class ApprovalService {
    private final ApprovalMapper approvalMapper;
    private final TeamMapper teamMapper;
    private final UserMapper userMapper;
    private final OrganizationService organizationService;

    @Transactional(readOnly = true)
    public List<ApprovalRouteRule> routeRules() {
        return approvalMapper.findAllRouteRules();
    }

    @Transactional(readOnly = true)
    public List<ApprovalRouteRule> routeRules(ApprovalDocumentType documentType) {
        return approvalMapper.findAllRouteRules().stream()
                .filter(rule -> rule.getDocumentType() == documentType)
                .toList();
    }

    public record RouteRuleIssue(String severity, String documentType, String requesterLevel,
                                 String message, String actionUrl) {
    }

    public record RouteReadinessIssue(String severity, String employeeName, String teamName,
                                      String documentType, String message, String actionUrl) {
    }

    public record RouteSummary(RequesterLevel level, Long scopeTeamId, String levelLabel,
                               List<String> chain, String receivers, boolean active) {
    }

    @Transactional(readOnly = true)
    public List<RouteSummary> routeSummaries(ApprovalDocumentType documentType) {
        List<RouteSummary> summaries = new ArrayList<>();
        List<ApprovalRouteRule> allRules = routeRules(documentType);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ApprovalRouteRule seed : allRules) {
            String key = (seed.getScopeTeamId() == null ? "DEFAULT" : seed.getScopeTeamId()) + ":" + seed.getRequesterLevel();
            if (!seen.add(key)) continue;
            RequesterLevel level = seed.getRequesterLevel();
            Long scopeTeamId = seed.getScopeTeamId();
            List<ApprovalRouteRule> rules = allRules.stream()
                    .filter(rule -> Objects.equals(rule.getScopeTeamId(), scopeTeamId)
                            && rule.getRequesterLevel() == level)
                    .toList();
            List<String> chain = new ArrayList<>();
            LinkedHashSet<Long> receiverIds = new LinkedHashSet<>();
            boolean anyActive = false;
            for (ApprovalRouteRule r : rules) {
                chain.add(routeRuleLabel(r));
                for (Long rid : approvalMapper.findRouteReceiverTeamIds(r.getId())) {
                    if (rid != null) {
                        receiverIds.add(rid);
                    }
                }
                if (Boolean.TRUE.equals(r.getActive())) {
                    anyActive = true;
                }
            }
            List<String> receiverNames = new ArrayList<>();
            for (Long rid : receiverIds) {
                Team team = teamMapper.findById(rid);
                if (team != null) {
                    receiverNames.add(team.getName());
                }
            }
            String levelLabel = scopeTeamId == null
                    ? level.getLabel()
                    : (StringUtils.hasText(seed.getScopeTeamName()) ? seed.getScopeTeamName() : "지정 조직") + " " + level.getLabel();
            summaries.add(new RouteSummary(level, scopeTeamId, levelLabel, chain,
                    receiverNames.isEmpty() ? "-" : String.join(", ", receiverNames), anyActive));
        }
        return summaries;
    }

    @Transactional(readOnly = true)
    public List<RouteRuleIssue> routeRuleIssues(CustomUserDetails currentUser) {
        requireAdmin(currentUser);
        List<RouteRuleIssue> issues = new ArrayList<>();
        for (ApprovalDocumentType type : ApprovalDocumentType.values()) {
            for (RequesterLevel level : RequesterLevel.values()) {
                List<ApprovalRouteRule> rules = approvalMapper.findRouteRules(type, level);
                String actionUrl = "/admin/approval-routes/chain?type=" + type.name() + "&level=" + level.name();
                if (rules.isEmpty()) {
                    issues.add(new RouteRuleIssue("danger", type.getLabel(), level.getLabel(),
                            "결재 경로가 없습니다. 해당 직책의 신청 버튼이 비활성화됩니다.", actionUrl));
                    continue;
                }
                boolean active = rules.stream().anyMatch(rule -> Boolean.TRUE.equals(rule.getActive()));
                if (!active) {
                    issues.add(new RouteRuleIssue("danger", type.getLabel(), level.getLabel(),
                            "등록된 결재 경로가 모두 비활성 상태입니다.", actionUrl));
                }
                for (ApprovalRouteRule rule : rules) {
                    if (rule.getApproverType() == ApproverType.USER) {
                        UserAccount approver = rule.getApproverUserId() == null ? null : userMapper.findById(rule.getApproverUserId());
                        if (approver == null || !Boolean.TRUE.equals(approver.getActive())) {
                            issues.add(new RouteRuleIssue("warn", type.getLabel(), level.getLabel(),
                                    rule.getStepOrder() + "차 직접 지정 결재자를 확인해야 합니다.", actionUrl));
                        }
                    } else if (rule.getApproverType() == ApproverType.ORG_LEADER) {
                        Team approverTeam = rule.getApproverTeamId() == null ? null : teamMapper.findById(rule.getApproverTeamId());
                        UserAccount leader = approverTeam == null || approverTeam.getLeaderUserId() == null ? null : userMapper.findById(approverTeam.getLeaderUserId());
                        if (approverTeam == null || !Boolean.TRUE.equals(approverTeam.getActive())
                                || leader == null || !Boolean.TRUE.equals(leader.getActive())) {
                            issues.add(new RouteRuleIssue("warn", type.getLabel(), level.getLabel(),
                                    rule.getStepOrder() + "차 지정 조직장을 확인해야 합니다.", actionUrl));
                        }
                    }
                }
            }
        }
        return issues;
    }

    @Transactional(readOnly = true)
    public List<RouteReadinessIssue> routeReadinessIssues(CustomUserDetails currentUser) {
        requireAdmin(currentUser);
        List<RouteReadinessIssue> issues = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (UserAccount user : userMapper.findAllActive()) {
            if (Boolean.TRUE.equals(user.getSystemAccount())) {
                continue;
            }
            user.setRoles(userMapper.findRolesByUserId(user.getId()));
            for (ApprovalDocumentType type : ApprovalDocumentType.values()) {
                String problem = routeProblem(resolveRoute(type, user.getId(), today, today, null));
                if (problem != null) {
                    issues.add(new RouteReadinessIssue("danger", user.getName(),
                            StringUtils.hasText(user.getTeamName()) ? user.getTeamName() : "미지정",
                            type.getLabel(), problem, "/admin/users/" + user.getId() + "/edit"));
                }
            }
        }
        return issues;
    }

    @Transactional(readOnly = true)
    public ApprovalRouteChainForm routeChain(ApprovalDocumentType documentType, RequesterLevel requesterLevel) {
        return routeChain(documentType, requesterLevel, null);
    }

    @Transactional(readOnly = true)
    public ApprovalRouteChainForm routeChain(ApprovalDocumentType documentType, RequesterLevel requesterLevel, Long scopeTeamId) {
        ApprovalRouteChainForm form = new ApprovalRouteChainForm();
        form.setDocumentType(documentType);
        form.setRequesterLevel(requesterLevel);
        form.setScopeTeamId(scopeTeamId);
        List<ApprovalRouteRule> rules = approvalMapper.findRouteRulesByScope(documentType, requesterLevel, scopeTeamId);
        List<ApprovalRouteChainForm.Step> steps = new ArrayList<>();
        LinkedHashSet<Long> receivers = new LinkedHashSet<>();
        boolean anyActive = false;
        for (ApprovalRouteRule r : rules) {
            ApprovalRouteChainForm.Step s = new ApprovalRouteChainForm.Step();
            s.setApproverType(r.getApproverType());
            s.setApproverUserId(r.getApproverUserId());
            s.setApproverTeamId(r.getApproverTeamId());
            steps.add(s);
            for (Long rid : approvalMapper.findRouteReceiverTeamIds(r.getId())) {
                if (rid != null) {
                    receivers.add(rid);
                }
            }
            if (Boolean.TRUE.equals(r.getActive())) {
                anyActive = true;
            }
        }
        form.setSteps(steps);
        form.setReceiverTeamIds(new ArrayList<>(receivers));
        form.setActive(true);
        return form;
    }

    @Transactional
    public void saveRouteChain(ApprovalRouteChainForm form, CustomUserDetails currentUser) {
        requireAdmin(currentUser);
        if (form.getDocumentType() == null || form.getRequesterLevel() == null) {
            throw new BusinessException("결재 종류와 신청자 직책을 선택하세요.");
        }
        if (form.getScopeTeamId() != null) {
            Team scopeTeam = teamMapper.findById(form.getScopeTeamId());
            if (scopeTeam == null || !Boolean.TRUE.equals(scopeTeam.getActive())) {
                throw new BusinessException("특정 조직 규칙의 조직을 찾을 수 없습니다.");
            }
        }
        List<ApprovalRouteChainForm.Step> steps = form.getSteps() == null ? List.of()
                : form.getSteps().stream()
                        .filter(s -> s != null && s.getApproverType() != null)
                        .toList();
        if (steps.isEmpty()) {
            throw new BusinessException("결재 단계를 1개 이상 추가하세요.");
        }
        for (ApprovalRouteChainForm.Step s : steps) {
            if (s.getApproverType() == ApproverType.USER && s.getApproverUserId() == null) {
                throw new BusinessException("직접 지정 단계는 결재권자를 선택하세요.");
            }
            if (s.getApproverType() == ApproverType.USER) {
                UserAccount approver = userMapper.findById(s.getApproverUserId());
                if (approver == null || !Boolean.TRUE.equals(approver.getActive())) {
                    throw new BusinessException("직접 지정 결재권자를 찾을 수 없습니다.");
                }
            }
            if (s.getApproverType() == ApproverType.ORG_LEADER && s.getApproverTeamId() == null) {
                throw new BusinessException("조직장 단계는 조직을 선택하세요.");
            }
            if (s.getApproverType() == ApproverType.ORG_LEADER) {
                Team approverTeam = teamMapper.findById(s.getApproverTeamId());
                if (approverTeam == null || !Boolean.TRUE.equals(approverTeam.getActive())) {
                    throw new BusinessException("조직장 결재 조직을 찾을 수 없습니다.");
                }
            }
        }
        List<Long> receiverIds = form.getReceiverTeamIds() == null ? List.of()
                : form.getReceiverTeamIds().stream().filter(Objects::nonNull).distinct().toList();
        for (Long rid : receiverIds) {
            if (teamMapper.findById(rid) == null) {
                throw new BusinessException("수신 부서를 찾을 수 없습니다.");
            }
        }
        for (ApprovalRouteRule existing : approvalMapper.findRouteRulesByScope(
                form.getDocumentType(), form.getRequesterLevel(), form.getScopeTeamId())) {
            approvalMapper.deleteRouteReceivers(existing.getId());
            approvalMapper.deleteRouteRule(existing.getId());
        }
        int order = 1;
        for (ApprovalRouteChainForm.Step s : steps) {
            ApprovalRouteRule rule = new ApprovalRouteRule();
            rule.setId(null);
            rule.setDocumentType(form.getDocumentType());
            rule.setScopeTeamId(form.getScopeTeamId());
            rule.setRequesterLevel(form.getRequesterLevel());
            rule.setStepOrder(order);
            rule.setApproverType(s.getApproverType());
            rule.setApproverUserId(s.getApproverType() == ApproverType.USER ? s.getApproverUserId() : null);
            rule.setApproverTeamId(s.getApproverType() == ApproverType.ORG_LEADER ? s.getApproverTeamId() : null);
            rule.setReceiverTeamId(null);
            rule.setActive(true);
            approvalMapper.insertRouteRule(rule);
            if (order == 1 && !receiverIds.isEmpty()) {
                Long ruleId = approvalMapper.findRouteRuleId(
                        form.getDocumentType(), form.getRequesterLevel(), form.getScopeTeamId(), 1);
                if (ruleId != null) {
                    approvalMapper.deleteRouteReceivers(ruleId);
                    for (Long rid : receiverIds) {
                        approvalMapper.insertRouteReceiver(ruleId, rid);
                    }
                }
            }
            order++;
        }
    }

    @Transactional
    public void deleteRouteChain(ApprovalDocumentType documentType, RequesterLevel requesterLevel,
                                 CustomUserDetails currentUser) {
        deleteRouteChain(documentType, requesterLevel, null, currentUser);
    }

    @Transactional
    public void deleteRouteChain(ApprovalDocumentType documentType, RequesterLevel requesterLevel,
                                 Long scopeTeamId, CustomUserDetails currentUser) {
        requireAdmin(currentUser);
        if (documentType == null || requesterLevel == null) {
            throw new BusinessException("삭제할 결재 경로를 찾을 수 없습니다.");
        }
        for (ApprovalRouteRule existing : approvalMapper.findRouteRulesByScope(documentType, requesterLevel, scopeTeamId)) {
            approvalMapper.deleteRouteReceivers(existing.getId());
            approvalMapper.deleteRouteRule(existing.getId());
        }
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> previewRoute(CustomUserDetails currentUser, ApprovalDocumentType documentType, LocalDate startDate, LocalDate endDate) {
        return buildSteps(documentType, currentUser.getUserId(), startDate, endDate, null);
    }

    /** 임의 신청자 기준 결재선 미리보기 — 취소 결재처럼 기안자가 현재 사용자와 다를 수 있는 경우. */
    @Transactional(readOnly = true)
    public List<ApprovalStep> previewRouteFor(Long requesterUserId, ApprovalDocumentType documentType, LocalDate startDate, LocalDate endDate) {
        return buildSteps(documentType, requesterUserId, startDate, endDate, null);
    }

    @Transactional
    public List<ApprovalStep> createSteps(ApprovalDocumentType documentType, Long documentId, Long requesterUserId,
                                          LocalDate startDate, LocalDate endDate) {
        approvalMapper.deleteSteps(documentType, documentId);
        approvalMapper.deleteDocumentReceivers(documentType, documentId);
        RouteResult route = resolveRoute(documentType, requesterUserId, startDate, endDate, documentId);
        String problem = routeProblem(route);
        if (problem != null) {
            throw new BusinessException(problem);
        }
        List<ApprovalStep> steps = route.steps();
        for (ApprovalStep step : steps) {
            approvalMapper.insertStep(step);
        }
        createDocumentReceivers(documentType, documentId, requesterUserId);
        return steps;
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> steps(ApprovalDocumentType documentType, Long documentId) {
        return approvalMapper.findSteps(documentType, documentId);
    }

    @Transactional
    public void deleteSteps(ApprovalDocumentType documentType, Long documentId) {
        approvalMapper.deleteSteps(documentType, documentId);
        approvalMapper.deleteDocumentReceivers(documentType, documentId);
    }

    @Transactional(readOnly = true)
    public ApprovalStep pendingStep(ApprovalDocumentType documentType, Long documentId) {
        return approvalMapper.findPendingStep(documentType, documentId);
    }

    @Transactional
    public ApprovalStep approvePendingStep(ApprovalDocumentType documentType, Long documentId, CustomUserDetails currentUser) {
        ApprovalStep step = requireActionableStep(documentType, documentId, currentUser);
        approvalMapper.updateStepStatus(step.getId(), ApprovalStatus.APPROVED, currentUser.getUserId(), null);
        return step;
    }

    @Transactional
    public ApprovalStep rejectPendingStep(ApprovalDocumentType documentType, Long documentId, CustomUserDetails currentUser, String reason) {
        ApprovalStep step = requireActionableStep(documentType, documentId, currentUser);
        approvalMapper.updateStepStatus(step.getId(), ApprovalStatus.REJECTED, currentUser.getUserId(), trimToNull(reason));
        return step;
    }

    @Transactional(readOnly = true)
    public List<ApprovalInboxItem> inbox(CustomUserDetails currentUser) {
        if (currentUser != null && currentUser.isSuperAdmin()) {
            return approvalMapper.findDocumentItems(currentUser.getUserId(), currentUser.getTeamId(), "pending", true);
        }
        return approvalMapper.findInboxItems(currentUser.getUserId());
    }

    @Transactional(readOnly = true)
    public List<ApprovalInboxItem> documents(CustomUserDetails currentUser, String box) {
        String requestedBox = box == null ? "" : box.trim();
        String normalized = switch (requestedBox) {
            case "pending", "requested", "completed", "sent", "received", "after" -> requestedBox;
            default -> "pending";
        };
        boolean superAdmin = currentUser != null && currentUser.isSuperAdmin();
        return approvalMapper.findDocumentItems(currentUser.getUserId(), currentUser.getTeamId(), normalized, superAdmin);
    }

    @Transactional
    public ApprovalStep postApproveStep(ApprovalDocumentType documentType, Long documentId, CustomUserDetails currentUser) {
        ApprovalStep step = approvalMapper.findPostApprovalStep(documentType, documentId, currentUser.getUserId());
        if (step == null) {
            throw new BusinessException("후결 처리할 문서가 아닙니다.");
        }
        approvalMapper.updatePostApprovalStep(step.getId(), currentUser.getUserId());
        return step;
    }

    @Transactional(readOnly = true)
    public boolean canPostApproveStep(ApprovalDocumentType documentType, Long documentId, CustomUserDetails currentUser) {
        return currentUser != null
                && approvalMapper.findPostApprovalStep(documentType, documentId, currentUser.getUserId()) != null;
    }

    @Transactional(readOnly = true)
    public boolean canViewDocument(CustomUserDetails currentUser, ApprovalDocumentType documentType, Long documentId) {
        if (currentUser == null || documentType == null || documentId == null) {
            return false;
        }
        if (currentUser.isSuperAdmin()) {
            return true;
        }
        boolean relatedStep = approvalMapper.findSteps(documentType, documentId).stream()
                .anyMatch(step -> Objects.equals(step.getApproverUserId(), currentUser.getUserId())
                        || Objects.equals(step.getDelegateUserId(), currentUser.getUserId())
                        || Objects.equals(step.getActedBy(), currentUser.getUserId()));
        if (relatedStep) {
            return true;
        }
        if (currentUser.getTeamId() != null
                && approvalMapper.existsDocumentReceiver(documentType, documentId, currentUser.getTeamId())) {
            return true;
        }
        // [R-1] 같은 팀(조직 소속) 직원이면 휴가/근태/시차/출장 문서 view 가능
        if (currentUser.getTeamId() != null) {
            Long requesterTeamId = approvalMapper.findRequesterTeamId(documentType, documentId);
            if (requesterTeamId != null && requesterTeamId.equals(currentUser.getTeamId())) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<ApprovalDelegation> delegations(CustomUserDetails currentUser) {
        return approvalMapper.findDelegations(currentUser.getUserId());
    }

    /** [UX41] 해당 기간 전체를 덮는 활성 대리승인 지정이 있는지 — 휴가/출장 신청 시 안내용. */
    @Transactional(readOnly = true)
    public boolean hasDelegationCovering(Long userId, LocalDate start, LocalDate end) {
        if (userId == null || start == null || end == null) return true;
        return approvalMapper.findDelegations(userId).stream()
                .filter(d -> Boolean.TRUE.equals(d.getActive()))
                .anyMatch(d -> !start.isBefore(d.getStartDate()) && !end.isAfter(d.getEndDate()));
    }

    /** [UX42] 캘린더 배지용 결재 진행 요약 — 요청함 / 진행중(누구 완·미완) / 완료 / 반려. */
    public record ApprovalProgress(String label, String css, String detail) {}

    @Transactional(readOnly = true)
    public ApprovalProgress approvalProgress(ApprovalDocumentType documentType, Long documentId, String requestStatus) {
        if ("REJECTED".equals(requestStatus)) {
            return new ApprovalProgress("반려", "bad", "결재가 반려되었습니다.");
        }
        List<ApprovalStep> steps = approvalMapper.findSteps(documentType, documentId);
        if ("APPROVED".equals(requestStatus)
                || (!steps.isEmpty() && steps.stream().allMatch(s -> s.getStatus() == ApprovalStatus.APPROVED))) {
            return new ApprovalProgress("결재완료", "done",
                    steps.stream().map(s -> stepPersonName(s) + " 완").collect(java.util.stream.Collectors.joining(" · ")));
        }
        String detail = steps.stream()
                .map(s -> stepPersonName(s) + (s.getStatus() == ApprovalStatus.APPROVED ? " 완" : " 미완"))
                .collect(java.util.stream.Collectors.joining(" · "));
        boolean anyApproved = steps.stream().anyMatch(s -> s.getStatus() == ApprovalStatus.APPROVED);
        return anyApproved
                ? new ApprovalProgress("결재 진행중", "prog", detail)
                : new ApprovalProgress("결재 요청함", "req", detail.isEmpty() ? "결재 대기 중입니다." : detail);
    }

    private String stepPersonName(ApprovalStep step) {
        return step.getApproverName() != null ? step.getApproverName()
                : (step.getActedByName() != null ? step.getActedByName() : "결재자");
    }

    @Transactional
    public void createDelegation(CustomUserDetails currentUser, ApprovalDelegationForm form) {
        if (form.delegateUserId() == null || form.startDate() == null || form.endDate() == null) {
            throw new BusinessException("대리승인자와 기간을 입력하세요.");
        }
        if (Objects.equals(currentUser.getUserId(), form.delegateUserId())) {
            throw new BusinessException("본인을 대리승인자로 지정할 수 없습니다.");
        }
        if (form.startDate().isAfter(form.endDate())) {
            throw new BusinessException("대리승인 종료일은 시작일보다 빠를 수 없습니다.");
        }
        UserAccount delegate = userMapper.findById(form.delegateUserId());
        if (delegate == null || !Boolean.TRUE.equals(delegate.getActive())) {
            throw new BusinessException("대리승인자를 찾을 수 없습니다.");
        }
        boolean overlap = approvalMapper.findDelegations(currentUser.getUserId()).stream()
                .filter(existing -> Boolean.TRUE.equals(existing.getActive()))
                .anyMatch(existing -> !form.endDate().isBefore(existing.getStartDate())
                        && !form.startDate().isAfter(existing.getEndDate()));
        if (overlap) {
            throw new BusinessException("같은 기간에 이미 사용중인 대리승인 지정이 있습니다. 기존 지정을 해제한 뒤 다시 지정하세요.");
        }
        ApprovalDelegation delegation = new ApprovalDelegation();
        delegation.setApproverUserId(currentUser.getUserId());
        delegation.setDelegateUserId(form.delegateUserId());
        delegation.setStartDate(form.startDate());
        delegation.setEndDate(form.endDate());
        delegation.setReason(trimToNull(form.reason()));
        approvalMapper.insertDelegation(delegation);
    }

    @Transactional
    public void cancelDelegation(CustomUserDetails currentUser, Long id) {
        approvalMapper.deactivateDelegation(id, currentUser.getUserId());
    }

    private record RouteResult(List<ApprovalStep> steps, List<String> missing, boolean hadRules) {
    }

    private List<ApprovalStep> buildSteps(ApprovalDocumentType documentType, Long requesterUserId,
                                          LocalDate startDate, LocalDate endDate, Long documentId) {
        return resolveRoute(documentType, requesterUserId, startDate, endDate, documentId).steps();
    }

    private RouteResult resolveRoute(ApprovalDocumentType documentType, Long requesterUserId,
                                     LocalDate startDate, LocalDate endDate, Long documentId) {
        UserAccount requester = userMapper.findById(requesterUserId);
        if (requester == null || !Boolean.TRUE.equals(requester.getActive())) {
            throw new BusinessException("신청자를 찾을 수 없습니다.");
        }
        requester.setRoles(userMapper.findRolesByUserId(requester.getId()));
        List<Team> ancestors = requester.getTeamId() == null ? List.of() : teamMapper.findAncestors(requester.getTeamId());
        RequesterLevel requesterLevel = requesterLevel(requester, ancestors);
        List<ApprovalRouteRule> rules = matchingRouteRules(documentType, requesterLevel, ancestors).stream()
                .filter(r -> Boolean.TRUE.equals(r.getActive()))
                .toList();
        List<ApprovalStep> steps = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<Long> approverIds = new LinkedHashSet<>();
        int stepOrder = 1;
        for (ApprovalRouteRule rule : rules) {
            Long approverId = resolveApprover(rule, requester, ancestors);
            if (approverId == null) {
                String label = routeRuleLabel(rule);
                if (!missing.contains(label)) {
                    missing.add(label);
                }
                continue;
            }
            boolean self = rule.getApproverType() == ApproverType.SELF;
            if (!self && Objects.equals(approverId, requesterUserId)) {
                continue;
            }
            if (!approverIds.add(approverId)) {
                continue;
            }
            steps.add(step(documentType, documentId, stepOrder++, approverId, rule.getApproverType(), startDate, endDate));
        }
        return new RouteResult(steps, missing, !rules.isEmpty());
    }

    public String routeAlert(CustomUserDetails currentUser, ApprovalDocumentType documentType,
                             LocalDate startDate, LocalDate endDate) {
        RouteResult r = resolveRoute(documentType, currentUser.getUserId(), startDate, endDate, null);
        return routeProblem(r);
    }

    private String routeProblem(RouteResult r) {
        if (!r.hadRules()) {
            return "아직 결재선이 없습니다. 관리자에게 문의하세요.";
        }
        if (!r.missing().isEmpty()) {
            return "결재선 '" + String.join(", ", r.missing())
                    + "'이(가) 부재입니다. 조직관리에서 해당 결재자를 지정하거나 관리자에게 문의하세요.";
        }
        if (r.steps().isEmpty()) {
            return "아직 결재선이 없습니다. 관리자에게 문의하세요.";
        }
        return null;
    }

    private void createDocumentReceivers(ApprovalDocumentType documentType, Long documentId, Long requesterUserId) {
        UserAccount requester = userMapper.findById(requesterUserId);
        if (requester == null) {
            return;
        }
        requester.setRoles(userMapper.findRolesByUserId(requester.getId()));
        List<Team> ancestors = requester.getTeamId() == null ? List.of() : teamMapper.findAncestors(requester.getTeamId());
        RequesterLevel requesterLevel = requesterLevel(requester, ancestors);
        Set<Long> receiverTeamIds = new LinkedHashSet<>();
        for (ApprovalRouteRule rule : matchingRouteRules(documentType, requesterLevel, ancestors).stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getActive()))
                .toList()) {
            for (Long receiverId : approvalMapper.findRouteReceiverTeamIds(rule.getId())) {
                if (receiverId != null && receiverTeamIds.add(receiverId)) {
                    approvalMapper.insertDocumentReceiver(documentType, documentId, receiverId);
                }
            }
        }
    }

    private List<ApprovalRouteRule> matchingRouteRules(ApprovalDocumentType documentType,
                                                       RequesterLevel requesterLevel,
                                                       List<Team> ancestors) {
        for (Team team : ancestors) {
            if (team.getId() == null) continue;
            List<ApprovalRouteRule> scoped = approvalMapper.findRouteRulesByScope(documentType, requesterLevel, team.getId()).stream()
                    .filter(rule -> Boolean.TRUE.equals(rule.getActive()))
                    .toList();
            if (!scoped.isEmpty()) {
                return scoped;
            }
        }
        return approvalMapper.findRouteRules(documentType, requesterLevel);
    }

    private ApprovalStep step(ApprovalDocumentType documentType, Long documentId, int stepOrder, Long approverId,
                              ApproverType approverType, LocalDate startDate, LocalDate endDate) {
        UserAccount approver = userMapper.findById(approverId);
        ApprovalDelegation delegation = approvalMapper.findActiveDelegationsForUser(approverId, startDate, endDate).stream()
                .findFirst()
                .orElse(null);
        ApprovalStep step = new ApprovalStep();
        step.setDocumentType(documentType);
        step.setDocumentId(documentId);
        step.setStepOrder(stepOrder);
        step.setApproverUserId(approverId);
        step.setApproverType(approverType);
        step.setApproverName(approver == null ? null : approver.getName());
        step.setApproverDept(approver == null ? null : approver.getDept());
        step.setApproverJobTitle(approver == null ? null : approver.getJobTitle());
        step.setDelegateUserId(delegation == null ? null : delegation.getDelegateUserId());
        step.setDelegateName(delegation == null ? null : delegation.getDelegateName());
        step.setStatus(ApprovalStatus.PENDING);
        return step;
    }

    private RequesterLevel requesterLevel(UserAccount requester, List<Team> ancestors) {
        if (leaderOf(requester.getId(), ancestors, "ROOT")) {
            return RequesterLevel.CEO;
        }
        if (leaderOf(requester.getId(), ancestors, "DIVISION")) {
            return RequesterLevel.DIVISION_LEADER;
        }
        if (leaderOf(requester.getId(), ancestors, "TEAM") || requester.hasRole("TEAM_LEADER")) {
            return RequesterLevel.TEAM_LEADER;
        }
        return RequesterLevel.MEMBER;
    }

    private boolean leaderOf(Long userId, List<Team> ancestors, String nodeType) {
        return ancestors.stream()
                .anyMatch(team -> nodeType.equals(team.getNodeType()) && Objects.equals(team.getLeaderUserId(), userId));
    }

    private Long resolveApprover(ApprovalRouteRule rule, UserAccount requester, List<Team> ancestors) {
        if (rule.getApproverType() == ApproverType.SELF) {
            return requester.getId();
        }
        if (rule.getApproverType() == ApproverType.USER) {
            return rule.getApproverUserId();
        }
        if (rule.getApproverType() == ApproverType.ORG_LEADER) {
            Team team = rule.getApproverTeamId() == null ? null : teamMapper.findById(rule.getApproverTeamId());
            return team == null || !Boolean.TRUE.equals(team.getActive()) ? null : team.getLeaderUserId();
        }
        if (rule.getApproverType() == ApproverType.TEAM_LEADER) {
            return firstLeader(ancestors, requester.getId(), "TEAM", null);
        }
        if (rule.getApproverType() == ApproverType.DIVISION_LEADER) {
            Long divisionLeader = firstLeader(ancestors, requester.getId(), "DIVISION", null);
            return divisionLeader == null ? firstLeader(ancestors, requester.getId(), "ROOT", null) : divisionLeader;
        }
        if (rule.getApproverType() == ApproverType.ROOT_LEADER) {
            return firstLeader(ancestors, requester.getId(), "ROOT", null);
        }
        return null;
    }

    private String routeRuleLabel(ApprovalRouteRule rule) {
        if (rule == null || rule.getApproverType() == null) {
            return "결재자";
        }
        if (rule.getApproverType() == ApproverType.USER) {
            return StringUtils.hasText(rule.getApproverName()) ? rule.getApproverName() : "직접 지정";
        }
        if (rule.getApproverType() == ApproverType.ORG_LEADER) {
            return StringUtils.hasText(rule.getApproverTeamName())
                    ? rule.getApproverTeamName() + " 조직장"
                    : "조직장";
        }
        return rule.getApproverType().getLabel();
    }

    private Long firstLeader(List<Team> ancestors, Long requesterId, String nodeType, Long excluded) {
        return ancestors.stream()
                .filter(team -> nodeType.equals(team.getNodeType()))
                .map(Team::getLeaderUserId)
                .filter(Objects::nonNull)
                .filter(id -> !Objects.equals(id, requesterId))
                .filter(id -> !Objects.equals(id, excluded))
                .findFirst()
                .orElse(null);
    }


    private ApprovalStep requireActionableStep(ApprovalDocumentType documentType, Long documentId, CustomUserDetails currentUser) {
        ApprovalStep step = approvalMapper.findPendingStep(documentType, documentId);
        if (step == null) {
            throw new BusinessException("결재 대기 단계가 없습니다.");
        }
        boolean approver = Objects.equals(step.getApproverUserId(), currentUser.getUserId());
        boolean delegate = Objects.equals(step.getDelegateUserId(), currentUser.getUserId());
        if (!approver && !delegate) {
            throw new BusinessException("현재 결재권자가 아닙니다.");
        }
        return step;
    }

    private void requireAdmin(CustomUserDetails currentUser) {
        if (!organizationService.canManageOrganization(currentUser)) {
            throw new BusinessException("결재 경로 관리 권한이 없습니다.");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
