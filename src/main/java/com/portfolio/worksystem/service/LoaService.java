package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.LoaMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.LoaAccrualTreatment;
import com.portfolio.worksystem.model.LoaRequest;
import com.portfolio.worksystem.model.LoaStatus;
import com.portfolio.worksystem.model.LoaType;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * [B7] 휴직 — 사유별 종류, 본인 신청·결재, 그리고 <b>연차 출근율 반영</b>.
 *
 * <p>휴직은 원래 {@code users.loa_start/loa_end} 두 날짜뿐이었고 인사담당자가 조직관리에서
 * 직접 넣는 것 말고는 경로가 없었다. 사유가 없어 <b>육아휴직인지 개인 사정인지</b>를 코드가
 * 알 수 없었고, 그래서 연차 부여에 전혀 반영되지 못했다 — 1년 내내 쉰 사람도 근속만 쌓여
 * 연차가 그대로 나왔고 그게 수당 정산까지 갔다.
 *
 * <p>이제 종류마다 {@link LoaAccrualTreatment} 가 붙는다. 육아휴직·업무상 재해는 법이 출근으로
 * 간주하므로 연차가 깎이지 않고, 그 밖의 휴직은 소정근로일에서 빼고 비례해서 준다.
 */
@Service
@RequiredArgsConstructor
public class LoaService {

    private final LoaMapper loaMapper;
    private final UserMapper userMapper;
    private final ApprovalService approvalService;
    private final HrScopeService hrScopeService;
    /** [B9] 인사정보 변경이력 — 휴직도 인사카드 이력에 남는다. */
    private final UserChangeLogService userChangeLogService;

    /* ── 종류 ─────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<LoaType> types() {
        return loaMapper.findTypes();
    }

    @Transactional(readOnly = true)
    public List<LoaType> activeTypes() {
        return loaMapper.findTypes().stream().filter(t -> !Boolean.FALSE.equals(t.getActive())).toList();
    }

    @Transactional(readOnly = true)
    public LoaType type(String code) {
        LoaType type = code == null ? null : loaMapper.findType(code);
        if (type == null) {
            throw new BusinessException("등록되지 않은 휴직 종류입니다: " + code);
        }
        return type;
    }

    @Transactional
    public void saveType(CustomUserDetails currentUser, LoaType form) {
        requireHrManager(currentUser);
        LoaType existing = type(form.getCode());
        if (!StringUtils.hasText(form.getLabel())) {
            throw new BusinessException("휴직 종류 이름을 입력하세요.");
        }
        if (LoaAccrualTreatment.fromCode(form.getAccrualTreatment()) == null) {
            throw new BusinessException("연차 반영 방식을 선택하세요.");
        }
        existing.setLabel(form.getLabel().trim());
        existing.setAccrualTreatment(form.getAccrualTreatment());
        existing.setEvidenceRequired(Boolean.TRUE.equals(form.getEvidenceRequired()));
        existing.setActive(Boolean.TRUE.equals(form.getActive()));
        existing.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        loaMapper.updateType(existing);
    }

    /* ── 조회 ─────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<LoaRequest> ownRequests(Long userId, Integer year) {
        return loaMapper.findRequests(userId, null, year);
    }

    @Transactional(readOnly = true)
    public List<LoaRequest> allRequests(CustomUserDetails currentUser, Integer year) {
        requireHrManager(currentUser);
        return loaMapper.findRequests(null, null, year);
    }

    @Transactional(readOnly = true)
    public List<LoaRequest> memberRequests(CustomUserDetails currentUser, Integer year) {
        Set<Long> scope = hrScopeService.memberScopeUserIds(currentUser);
        if (scope.isEmpty()) {
            return List.of();
        }
        return loaMapper.findRequests(null, null, year).stream()
                .filter(r -> scope.contains(r.getUserId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public LoaRequest request(Long id) {
        LoaRequest request = loaMapper.findRequest(id);
        if (request == null) {
            throw new BusinessException("휴직 신청을 찾을 수 없습니다.");
        }
        return request;
    }

    /* ── 신청·결재 ────────────────────────────────────── */

    /**
     * 본인 휴직 신청. 승인되어야 실제 휴직 상태가 된다.
     *
     * <p>{@code endDate} 가 null 이면 복직일 미정이다 — 흔한 경우라 막지 않는다.
     * 대신 겹치는 휴직이 이미 있으면 막는다(열린 구간은 그 뒤 전부와 겹친다).
     */
    @Transactional
    public void requestLoa(CustomUserDetails currentUser, String type, LocalDate startDate,
                           LocalDate endDate, String reason) {
        requestLoa(currentUser, type, startDate, endDate, reason, null);
    }

    /** [DLG] 휴직 신청 — 결재자라면 휴직 기간의 대리승인자를 함께 '예정'으로 지정한다. */
    @Transactional
    public void requestLoa(CustomUserDetails currentUser, String type, LocalDate startDate,
                           LocalDate endDate, String reason, Long delegateUserId) {
        LoaType def = type(type);
        if (!Boolean.TRUE.equals(def.getActive())) {
            throw new BusinessException("사용 중지된 휴직 종류입니다. 다른 종류를 선택하세요.");
        }
        if (startDate == null) {
            throw new BusinessException("휴직 시작일을 입력하세요.");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException("휴직 종료일은 시작일보다 빠를 수 없습니다.");
        }
        LoaRequest conflict = loaMapper.findOverlapping(currentUser.getUserId(), startDate, endDate, null);
        if (conflict != null) {
            throw new BusinessException("같은 기간에 이미 신청된 휴직이 있습니다 — "
                    + conflict.getTypeLabel() + " " + conflict.getPeriodText()
                    + "(" + conflict.getStatus().getLabel() + ").");
        }

        LoaRequest request = new LoaRequest();
        request.setUserId(currentUser.getUserId());
        request.setType(type);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setReason(StringUtils.hasText(reason) ? reason.trim() : null);
        request.setStatus(LoaStatus.PENDING);
        request.setCurrentApprovalStep(1);
        loaMapper.insertRequest(request);

        var steps = approvalService.createSteps(ApprovalDocumentType.LOA, request.getId(),
                currentUser.getUserId(), startDate, endDate == null ? startDate : endDate);
        if (steps.isEmpty()) {
            throw new BusinessException("휴직 결재경로가 설정되어 있지 않습니다.");
        }
        loaMapper.updateCurrentStep(request.getId(), steps.get(0).getStepOrder());
        approvalService.attachDelegation(currentUser.getUserId(), delegateUserId, startDate,
                endDate == null ? startDate.plusYears(1) : endDate, "휴직", ApprovalDocumentType.LOA, request.getId());
    }

    /**
     * 승인 — 이 시점에 users 의 휴직 상태가 실제로 바뀐다.
     *
     * <p>근태·휴가 신청·출근 체크가 모두 {@code UserAccount.isOnLeave} 를 보므로,
     * 여기서 반영하는 순간 그 화면들이 곧바로 휴직으로 동작한다.
     */
    @Transactional
    public void approve(CustomUserDetails currentUser, Long requestId) {
        LoaRequest request = request(requestId);
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 휴직만 승인할 수 있습니다.");
        }
        approvalService.approvePendingStep(ApprovalDocumentType.LOA, requestId, currentUser);
        var next = approvalService.pendingStep(ApprovalDocumentType.LOA, requestId);
        if (next != null) {
            // 아직 남은 결재 단계가 있다 — 여기서 재직상태를 바꾸면 안 된다.
            loaMapper.updateCurrentStep(requestId, next.getStepOrder());
            return;
        }
        loaMapper.updateStatus(requestId, LoaStatus.APPROVED.name(), currentUser.getUserId(), null);
        approvalService.activateLinkedDelegation(ApprovalDocumentType.LOA, requestId);
        loaMapper.applyToUser(request.getUserId(), request.getType(),
                request.getStartDate(), request.getEndDate());
        // 필드 세 개가 한꺼번에 바뀌지만 사람이 읽을 때는 '휴직 승인' 한 사건이다.
        userChangeLogService.recordEvent(request.getUserId(), "LOA", "휴직(결재 승인)",
                null, request.getTypeLabel() + " " + request.getPeriodText(), "LOA", currentUser);
    }

    @Transactional
    public void reject(CustomUserDetails currentUser, Long requestId, String reason) {
        LoaRequest request = request(requestId);
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 휴직만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.LOA, requestId, currentUser, reason);
        loaMapper.updateStatus(requestId, LoaStatus.REJECTED.name(), currentUser.getUserId(),
                StringUtils.hasText(reason) ? reason.trim() : null);
        approvalService.dropLinkedDelegation(ApprovalDocumentType.LOA, requestId);
    }

    @Transactional
    public void cancel(CustomUserDetails currentUser, Long requestId) {
        LoaRequest request = request(requestId);
        if (!request.getUserId().equals(currentUser.getUserId()) && !hrScopeService.canManageHr(currentUser)) {
            throw new BusinessException("본인 휴직 신청만 취소할 수 있습니다.");
        }
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 휴직만 취소할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.LOA, requestId);
        loaMapper.updateStatus(requestId, LoaStatus.CANCELED.name(), currentUser.getUserId(), null);
        approvalService.dropLinkedDelegation(ApprovalDocumentType.LOA, requestId);
    }

    /* ── 인사담당자 직접 지정 ─────────────────────────── */

    /**
     * 인사카드에서 인사담당자가 직접 휴직을 걸거나 푼다 — 결재를 거치지 않는다.
     *
     * <p>신청·결재 경로가 정상이지만, 이미 휴직 중인 사람을 시스템에 옮겨 적거나 소급 처리해야
     * 하는 경우가 있어 직접 경로를 남겨 둔다. {@code startDate} 를 비우면 <b>복직</b>이다.
     */
    @Transactional
    public String setLeaveOfAbsence(CustomUserDetails currentUser, Long userId, String type,
                                    LocalDate startDate, LocalDate endDate) {
        requireHrManager(currentUser);
        UserAccount target = userMapper.findById(userId);
        if (target == null) {
            throw new BusinessException("대상 직원을 찾을 수 없습니다.");
        }
        if (startDate == null) {
            if (endDate != null) {
                throw new BusinessException("휴직 시작일을 함께 입력하세요.");
            }
            archiveReturnedPeriod(target, currentUser);
            loaMapper.applyToUser(userId, null, null, null);
            userChangeLogService.recordEvent(userId, "LOA", "복직 처리",
                    target.getLoaStart() == null ? null
                            : target.getLoaStart() + " ~ " + (target.getLoaEnd() == null ? "미정" : target.getLoaEnd()),
                    null, "HR_CARD", currentUser);
            return target.getName() + " 님을 복직 처리했습니다.";
        }
        LoaType def = type(type);
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException("휴직 종료일은 시작일보다 빠를 수 없습니다.");
        }
        loaMapper.applyToUser(userId, def.getCode(), startDate, endDate);
        userChangeLogService.recordEvent(userId, "LOA", "휴직(인사담당자 지정)",
                target.getLoaStart() == null ? null : target.getLoaStart() + " ~ "
                        + (target.getLoaEnd() == null ? "미정" : target.getLoaEnd()),
                def.getLabel() + " " + startDate + " ~ " + (endDate == null ? "복직일 미정" : endDate.toString()),
                "HR_CARD", currentUser);
        return target.getName() + " 님을 " + def.getLabel() + "("
                + startDate + " ~ " + (endDate == null ? "복직일 미정" : endDate.toString()) + ")으로 설정했습니다.";
    }

    /* ── [B8] 퇴사 처리 ──────────────────────────────── */

    private void archiveReturnedPeriod(UserAccount target, CustomUserDetails currentUser) {
        if (target.getLoaStart() == null || target.getLoaType() == null) {
            return;
        }
        LocalDate lastLeaveDate = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        int updated = loaMapper.closeApprovedPeriod(target.getId(), target.getLoaType(), target.getLoaStart(),
                lastLeaveDate, currentUser.getUserId());
        if (updated == 0 && !target.getLoaStart().isAfter(lastLeaveDate)) {
            // 인사카드에서 직접 지정한 휴직은 신청서가 없어 복직 시 이력을 보존한다.
            LoaRequest history = new LoaRequest();
            history.setUserId(target.getId());
            history.setType(target.getLoaType());
            history.setStartDate(target.getLoaStart());
            history.setEndDate(target.getLoaEnd() != null && target.getLoaEnd().isBefore(lastLeaveDate)
                    ? target.getLoaEnd() : lastLeaveDate);
            history.setStatus(LoaStatus.APPROVED);
            history.setCurrentApprovalStep(1);
            history.setDecidedBy(currentUser.getUserId());
            history.setReason("인사담당자 복직 처리로 휴직 이력 보존");
            loaMapper.insertRequest(history);
        }
    }

    /**
     * 퇴사 처리 — 퇴사일을 넣고 계정을 비활성한다. 비우면 <b>퇴사 취소</b>다.
     *
     * <p>퇴사일이 들어가는 순간 그 해 연차 부여가 <b>재직 기간에 비례</b>해 다시 계산된다.
     * 6월에 그만둔 사람에게 연말 기준 부여를 그대로 두면 쓰지도 못할 연차가 미사용분으로
     * 남아 <b>수당이 과다 산정</b>된다.
     *
     * <p>{@code active} 를 함께 끄되 퇴사일과는 분리해 둔다 — 인수인계 기간 동안 계정을
     * 열어 두는 운영이 있을 수 있어, 나중에 조직관리에서 따로 되돌릴 수 있다.
     */
    @Transactional
    public String resign(CustomUserDetails currentUser, Long userId, LocalDate resignedOn, boolean deactivate) {
        requireHrManager(currentUser);
        UserAccount target = userMapper.findById(userId);
        if (target == null) {
            throw new BusinessException("대상 직원을 찾을 수 없습니다.");
        }
        UserAccount before = UserChangeLogService.snapshot(target);
        if (resignedOn == null) {
            target.setResignedOn(null);
            target.setActive(true);
            userMapper.updateUser(target);
            userChangeLogService.record(before, userMapper.findById(userId), "HR_CARD", currentUser);
            return target.getName() + " 님의 퇴사 처리를 취소했습니다.";
        }
        if (target.getHireDate() != null && resignedOn.isBefore(target.getHireDate())) {
            throw new BusinessException("퇴사일은 입사일보다 빠를 수 없습니다.");
        }
        target.setResignedOn(resignedOn);
        if (deactivate) {
            target.setActive(false);
        }
        userMapper.updateUser(target);
        userChangeLogService.record(before, userMapper.findById(userId), "HR_CARD", currentUser);
        return target.getName() + " 님을 " + resignedOn + " 퇴사로 처리했습니다."
                + (deactivate ? " 계정을 비활성했습니다." : " 계정은 그대로 두었습니다.")
                + " 그 해 연차는 재직 기간에 비례해 다시 계산됩니다.";
    }

    /* ── 연차 출근율 ──────────────────────────────────── */

    /**
     * 연차 부여에서 <b>빼야 할</b> 휴직 일수 — 사용자별, 해당 연도 기준.
     *
     * <p>{@link LoaAccrualTreatment#COUNTED_AS_WORK} 인 종류(육아휴직·업무상 재해)는 세지 않는다.
     * 사유를 알 수 없는 옛 데이터({@code loa_type} 이 null)도 세지 않는다 —
     * <b>모르는 채로 깎으면 육아휴직자가 손해를 본다.</b> 확실히 제외 대상인 것만 뺀다.
     *
     * <p>날짜 집합으로 모으는 이유는 신청서와 users 현재 휴직이 같은 기간을 중복해 담을 수
     * 있어서다. 집합이면 겹쳐도 한 번만 세어진다.
     */
    @Transactional(readOnly = true)
    public Map<Long, Set<LocalDate>> excludedLoaDatesByUser(int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        Map<Long, Set<LocalDate>> byUser = new HashMap<>();
        for (LoaRequest period : loaMapper.findApprovedPeriodsInYear(year)) {
            if (LoaAccrualTreatment.fromCode(period.getAccrualTreatment()) != LoaAccrualTreatment.EXCLUDED) {
                continue;
            }
            LocalDate from = period.getStartDate().isBefore(yearStart) ? yearStart : period.getStartDate();
            LocalDate to = period.getEndDate() == null || period.getEndDate().isAfter(yearEnd)
                    ? yearEnd : period.getEndDate();
            Set<LocalDate> dates = byUser.computeIfAbsent(period.getUserId(), k -> new java.util.HashSet<>());
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                dates.add(d);
            }
        }
        return byUser;
    }

    /** 화면 안내용 — 종류 코드 → 정의. */
    @Transactional(readOnly = true)
    public Map<String, LoaType> typeByCode() {
        Map<String, LoaType> map = new LinkedHashMap<>();
        loaMapper.findTypes().forEach(t -> map.put(t.getCode(), t));
        return map;
    }

    private void requireHrManager(CustomUserDetails currentUser) {
        if (!hrScopeService.canManageHr(currentUser)) {
            throw new BusinessException("HR 관리자 권한이 필요합니다.");
        }
    }
}
