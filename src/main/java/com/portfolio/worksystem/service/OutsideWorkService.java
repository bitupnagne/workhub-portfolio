package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.OutsideWorkMapper;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.AttendanceStatus;
import com.portfolio.worksystem.model.OutsideWorkRequest;
import com.portfolio.worksystem.model.OvertimeStatus;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * [OW] 외근 신청 — 정해진 출근·퇴근 시각에 걸치는 외근의 출퇴근 유예.
 *
 * <p>평소 외근에는 쓰지 않는다. 그날 정해진 근무시간(시차출근 조·특정일 예외 반영) 기준으로:
 * <ul>
 *   <li>나간 시각 ≤ 정해진 출근시각 → <b>출근 유예</b>: 출근을 안 찍었거나 늦게 찍었어도 출근 = 정해진 출근시각(지각 아님).</li>
 *   <li>종료 시각 ≥ 정해진 퇴근시각 → <b>퇴근 유예</b>: 퇴근을 안 찍었거나 일찍 찍었어도 퇴근 = 정해진 퇴근시각(조퇴·누락 아님).</li>
 *   <li>둘 다 → 하루 전체 외근: 출근~퇴근 = 정해진 시각으로 기록.</li>
 *   <li>중간에만 걸치는 외근(예: 13:00~17:30) → 신청은 받되 근태는 그대로.</li>
 * </ul>
 * 상태는 외근(OUTSIDE). 단, 출근 유예가 없는 날 이미 지각이면 지각은 그대로 둔다.
 * 반영 시점: 최종 승인, 그날 출근·퇴근을 찍을 때, 다음날 00:30(퇴근을 안 찍은 날).
 * 사후 신청은 근무한 달이 끝나기 전까지만 받는다(시간외 근무와 같은 규칙).
 */
@Service
@RequiredArgsConstructor
public class OutsideWorkService {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final OutsideWorkMapper outsideWorkMapper;
    private final HrMapper hrMapper;
    private final HrService hrService;
    private final ApprovalService approvalService;
    private final HrScopeService hrScopeService;

    @Transactional(readOnly = true)
    public OutsideWorkRequest request(Long id) {
        OutsideWorkRequest request = outsideWorkMapper.findById(id);
        if (request == null) {
            throw new BusinessException("외근 신청을 찾을 수 없습니다.");
        }
        return request;
    }

    /** 내 신청(최근순). */
    @Transactional(readOnly = true)
    public List<OutsideWorkRequest> mine(CustomUserDetails user, YearMonth month) {
        return outsideWorkMapper.findRequests(user.getUserId(), null, month.atDay(1), month.atEndOfMonth());
    }

    /** 인사 관리자는 전 직원, 조직장은 산하, 그 밖에는 없음. */
    @Transactional(readOnly = true)
    public List<OutsideWorkRequest> members(CustomUserDetails user, YearMonth month) {
        Collection<Long> scope = null;
        if (!hrScopeService.canManageHr(user)) {
            Set<Long> ids = new HashSet<>(hrScopeService.memberScopeUserIds(user));
            ids.remove(user.getUserId());
            if (ids.isEmpty()) {
                return List.of();
            }
            scope = ids;
        }
        return outsideWorkMapper.findRequests(null, scope, month.atDay(1), month.atEndOfMonth()).stream()
                .filter(r -> !r.getUserId().equals(user.getUserId()))
                .toList();
    }

    @Transactional
    public OutsideWorkRequest submit(CustomUserDetails currentUser, LocalDate workDate, LocalTime outTime, LocalTime endTime,
                                     String place, String reason, Long delegateUserId) {
        if (workDate == null || outTime == null || endTime == null) {
            throw new BusinessException("근무일과 나간 시각·종료 시각을 모두 입력하세요.");
        }
        if (!endTime.isAfter(outTime)) {
            throw new BusinessException("외근 종료 시각은 나간 시각보다 늦어야 합니다.");
        }
        if (!StringUtils.hasText(place)) {
            throw new BusinessException("외근 장소를 입력하세요.");
        }
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        if (workDate.isBefore(today) && !YearMonth.from(workDate).equals(YearMonth.from(today))) {
            throw new BusinessException("사후 신청은 근무한 달이 끝나기 전까지만 할 수 있습니다. ("
                    + workDate.getMonthValue() + "월 외근은 " + workDate.getMonthValue() + "월 말일까지)");
        }
        String block = hrService.todayCheckBlockReasonOn(currentUser.getUserId(), workDate);
        if (block != null) {
            throw new BusinessException("외근을 신청할 수 없습니다 — " + block);
        }
        if (outsideWorkMapper.findActiveOnDate(currentUser.getUserId(), workDate) != null) {
            throw new BusinessException("그날 이미 진행 중이거나 승인된 외근 신청이 있습니다.");
        }
        OutsideWorkRequest request = new OutsideWorkRequest();
        request.setUserId(currentUser.getUserId());
        request.setWorkDate(workDate);
        request.setOutTime(outTime);
        request.setEndTime(endTime);
        request.setPlace(place.trim());
        request.setReason(StringUtils.hasText(reason) ? reason.trim() : null);
        request.setStatus(OvertimeStatus.PENDING);
        request.setCurrentApprovalStep(1);
        if (!affectsAttendance(request)) {
            // 출근·퇴근 시각에 걸리지 않는 외근은 결재 없이 바로 등록 — 근태는 그대로, 직원 현황에 '외근'으로만 보인다.
            request.setStatus(OvertimeStatus.APPROVED);
            outsideWorkMapper.insert(request);
            outsideWorkMapper.updateStatus(request.getId(), OvertimeStatus.APPROVED.name(), currentUser.getUserId(), null);
            approvalService.attachDelegation(currentUser.getUserId(), delegateUserId, workDate, workDate,
                    "외근", ApprovalDocumentType.OUTSIDE_WORK, request.getId());
            approvalService.activateLinkedDelegation(ApprovalDocumentType.OUTSIDE_WORK, request.getId());
            return request;
        }
        outsideWorkMapper.insert(request);
        var steps = approvalService.createSteps(ApprovalDocumentType.OUTSIDE_WORK, request.getId(),
                currentUser.getUserId(), workDate, workDate);
        if (steps.isEmpty()) {
            throw new BusinessException("외근 결재경로가 설정되어 있지 않습니다.");
        }
        outsideWorkMapper.updateCurrentStep(request.getId(), steps.get(0).getStepOrder());
        approvalService.attachDelegation(currentUser.getUserId(), delegateUserId, workDate, workDate,
                "외근", ApprovalDocumentType.OUTSIDE_WORK, request.getId());
        return request;
    }

    @Transactional
    public void approve(CustomUserDetails currentUser, Long requestId) {
        OutsideWorkRequest request = request(requestId);
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 외근만 승인할 수 있습니다.");
        }
        approvalService.approvePendingStep(ApprovalDocumentType.OUTSIDE_WORK, requestId, currentUser);
        var next = approvalService.pendingStep(ApprovalDocumentType.OUTSIDE_WORK, requestId);
        if (next != null) {
            outsideWorkMapper.updateCurrentStep(requestId, next.getStepOrder());
            return;
        }
        outsideWorkMapper.updateStatus(requestId, OvertimeStatus.APPROVED.name(), currentUser.getUserId(), null);
        approvalService.activateLinkedDelegation(ApprovalDocumentType.OUTSIDE_WORK, requestId);
        reconcile(request.getUserId(), request.getWorkDate());
    }

    @Transactional
    public void reject(CustomUserDetails currentUser, Long requestId, String reason) {
        OutsideWorkRequest request = request(requestId);
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 외근만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.OUTSIDE_WORK, requestId, currentUser, reason);
        outsideWorkMapper.updateStatus(requestId, OvertimeStatus.REJECTED.name(), currentUser.getUserId(),
                StringUtils.hasText(reason) ? reason.trim() : null);
        approvalService.dropLinkedDelegation(ApprovalDocumentType.OUTSIDE_WORK, requestId);
    }

    @Transactional
    public void cancel(CustomUserDetails currentUser, Long requestId) {
        OutsideWorkRequest request = request(requestId);
        if (!request.getUserId().equals(currentUser.getUserId()) && !hrScopeService.canManageHr(currentUser)) {
            throw new BusinessException("본인 외근 신청만 회수할 수 있습니다.");
        }
        boolean selfApproved = request.getStatus() == OvertimeStatus.APPROVED && !affectsAttendance(request);
        if (!request.isPending() && !selfApproved) {
            throw new BusinessException("승인 대기 상태의 외근만 회수할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.OUTSIDE_WORK, requestId);
        outsideWorkMapper.updateStatus(requestId, OvertimeStatus.CANCELED.name(), currentUser.getUserId(), null);
        approvalService.dropLinkedDelegation(ApprovalDocumentType.OUTSIDE_WORK, requestId);
    }

    /** 외근 시간이 정해진 출근시각·퇴근시각을 포함하는지. 휴무일이면 둘 다 아니다. */
    private record Effect(boolean coversStart, boolean coversEnd, HrService.WorkSchedule schedule) {
        boolean any() {
            return coversStart || coversEnd;
        }
    }

    private Effect effect(OutsideWorkRequest request) {
        if (hrService.isOffDay(request.getWorkDate())) {
            return new Effect(false, false, null);
        }
        HrService.WorkSchedule schedule = hrService.workSchedule(request.getUserId(), request.getWorkDate());
        if (schedule == null || schedule.startTime() == null || schedule.endTime() == null) {
            return new Effect(false, false, null);
        }
        LocalTime out = request.getOutTime();
        LocalTime end = request.getEndTime();
        // 출근시각이 외근 시간 안에 들어야 출근 유예(나간 시각 ≤ 출근시각 < 종료 시각), 퇴근도 같은 방식
        boolean coversStart = !out.isAfter(schedule.startTime()) && end.isAfter(schedule.startTime());
        boolean coversEnd = !end.isBefore(schedule.endTime()) && out.isBefore(schedule.endTime());
        return new Effect(coversStart, coversEnd, schedule);
    }

    /** 정해진 출근·퇴근 시각에 걸쳐 근태를 유예받는 외근인지 — 이때만 결재를 올린다. */
    boolean affectsAttendance(OutsideWorkRequest request) {
        return effect(request).any();
    }

    /** 정해진 출근·퇴근 시각에 걸치는지 — 화면 안내용. */
    @Transactional(readOnly = true)
    public String effectLabel(OutsideWorkRequest request) {
        Effect e = effect(request);
        return e.coversStart() && e.coversEnd() ? "출근·퇴근 유예"
                : e.coversStart() ? "출근 유예" : e.coversEnd() ? "퇴근 유예" : "근태 변화 없음";
    }

    /** 퇴근을 안 찍은 날도 다음날 새벽에 퇴근 유예를 반영한다. */
    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Seoul")
    public void reconcileYesterday() {
        LocalDate yesterday = LocalDate.now(SERVICE_ZONE).minusDays(1);
        for (OutsideWorkRequest r : outsideWorkMapper.findRequests(null, null, yesterday, yesterday)) {
            if (r.getStatus() == OvertimeStatus.APPROVED) {
                reconcile(r.getUserId(), r.getWorkDate());
            }
        }
    }

    @Transactional
    public void reconcile(Long userId, LocalDate workDate) {
        reconcile(userId, workDate, LocalDateTime.now(SERVICE_ZONE));
    }

    /** 승인된 외근의 출퇴근 유예를 그날 근태에 반영한다. 여러 번 불러도 결과는 같다. */
    void reconcile(Long userId, LocalDate workDate, LocalDateTime now) {
        OutsideWorkRequest request = outsideWorkMapper.findApprovedOnDate(userId, workDate);
        if (request == null) {
            return;
        }
        Effect effect = effect(request);
        if (!effect.any()) {
            return; // 중간 외근·휴무일 — 근태 변화 없음
        }
        HrService.WorkSchedule schedule = effect.schedule();
        boolean coversStart = effect.coversStart();
        boolean coversEnd = effect.coversEnd();
        LocalDateTime start = LocalDateTime.of(workDate, schedule.startTime());
        LocalDateTime end = LocalDateTime.of(workDate, schedule.endTime());
        AttendanceRecord record = hrMapper.findAttendanceByUserDate(userId, workDate);
        if (record == null) {
            if (coversStart && coversEnd) {
                AttendanceRecord created = new AttendanceRecord();
                created.setUserId(userId);
                created.setWorkDate(workDate);
                created.setCheckInAt(start);
                created.setCheckOutAt(end);
                created.setStatus(AttendanceStatus.OUTSIDE);
                created.setNote("외근(출근·퇴근 유예)");
                created.setSource("OUTSIDE_WORK");
                hrMapper.insertAttendance(created);
                outsideWorkMapper.markAttendanceApplied(request.getId());
            }
            return; // 출근 유예만 있는 날은 출근을 찍을 때, 퇴근 유예만 있는 날은 출근 기록이 있어야 반영
        }
        LocalDateTime in = record.getCheckInAt();
        LocalDateTime out = record.getCheckOutAt();
        boolean changed = false;
        if (coversStart && (in == null || in.isAfter(start))) {
            in = start;
            changed = true;
        }
        // 퇴근 유예는 퇴근을 찍었거나 정해진 퇴근시각이 지난 뒤에만 — 근무 중에 미리 닫지 않는다
        if (coversEnd && in != null && (out != null || !now.isBefore(end)) && (out == null || out.isBefore(end))) {
            out = end;
            changed = true;
        }
        if (!changed) {
            return;
        }
        AttendanceStatus status = !coversStart && record.getStatus() == AttendanceStatus.LATE
                ? AttendanceStatus.LATE : AttendanceStatus.OUTSIDE;
        hrMapper.updateAttendanceRecord(record.getId(), in, out, status);
        outsideWorkMapper.markAttendanceApplied(request.getId());
    }
}
