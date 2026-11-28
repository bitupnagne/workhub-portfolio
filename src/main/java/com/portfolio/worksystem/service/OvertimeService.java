package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.OvertimeMapper;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.AttendanceWeekDay;
import com.portfolio.worksystem.model.CompanyHoliday;
import com.portfolio.worksystem.model.OvertimeDayType;
import com.portfolio.worksystem.model.OvertimeGroupRule;
import com.portfolio.worksystem.model.OvertimeRequest;
import com.portfolio.worksystem.model.OvertimeSetting;
import com.portfolio.worksystem.model.OvertimeStatus;
import com.portfolio.worksystem.model.ShiftGroupDef;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * [OT] 시간외 근무 — 설정과 신청·결재.
 *
 * <p><b>신청·승인된 것만 인정한다</b>가 원칙이다. 다만 급한 일로 신청 없이 나와 일한 경우가 있으므로
 * 이미 지난 근무를 올리는 <b>사후 신청</b>을 받는다 — 근무한 달이 끝나기 전까지만.
 * 달을 넘기면 그달 집계가 이미 급여로 넘어갔을 수 있어, 뒤늦게 시간이 바뀌면 정산이 어긋난다.
 */
@Service
@RequiredArgsConstructor
public class OvertimeService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final BigDecimal MAX_RATE = new BigDecimal("9.99");
    /** 주 소정근로 40시간(분). 주간 한도는 여기에 그 주의 시간외 신청을 더해 본다. */
    private static final int STANDARD_WEEK_MINUTES = 40 * 60;

    private final OvertimeMapper overtimeMapper;
    private final HrMapper hrMapper;
    private final HrService hrService;
    private final ApprovalService approvalService;
    private final HrScopeService hrScopeService;

    /* ── 설정 ─────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public OvertimeSetting setting() {
        OvertimeSetting setting = overtimeMapper.findSetting();
        if (setting == null) {
            throw new BusinessException("시간외 근무 설정을 찾을 수 없습니다.");
        }
        return setting;
    }

    @Transactional
    public void saveSetting(CustomUserDetails currentUser, OvertimeSetting form) {
        requireHrManager(currentUser);
        if (form.getNightStart() == null || form.getNightEnd() == null) {
            throw new BusinessException("야간 시간대의 시작·종료 시각을 모두 입력하세요.");
        }
        requireRate("연장", form.getRateExtended());
        requireRate("야간", form.getRateNight());
        requireRate("휴일", form.getRateHoliday());
        requireRate("휴일 초과", form.getRateHolidayOver());
        requireRate("공휴일", form.getRatePublicHoliday());
        requireRate("근로자의 날", form.getRateLaborDay());
        requireRange("휴일 초과 기준 시간", form.getHolidayOverHours(), 1, 24);
        requireRange("주 최대 근로시간", form.getWeeklyLimitHours(), 1, 168);
        requireRange("통상시급 산정 기준 시간", form.getDailyStandardHours(), 1, 24);
        requireRange("미신청 안내 기준(분)", form.getNoticeThresholdMinutes(), 1, 600);
        form.setUpdatedBy(currentUser.getUserId());
        overtimeMapper.updateSetting(form);
    }

    /**
     * 조별 기준 — <b>기본 근무 + 사용 중인 시차출근 조 전부</b>를 돌려준다.
     * 따로 저장하지 않은 조는 기본 근무의 값을 물려받아 보여 준다({@code configured = false}).
     */
    @Transactional(readOnly = true)
    public List<OvertimeGroupRule> groupRules() {
        Map<String, OvertimeGroupRule> saved = new HashMap<>();
        for (OvertimeGroupRule rule : overtimeMapper.findGroupRules()) {
            saved.put(rule.getGroupCode(), rule);
        }
        OvertimeGroupRule base = saved.get(OvertimeGroupRule.DEFAULT_GROUP);
        if (base == null) {
            base = new OvertimeGroupRule();
            base.setGroupCode(OvertimeGroupRule.DEFAULT_GROUP);
            base.setExtendedAfterMinutes(480);
            base.setInclusiveMonthlyMinutes(0);
        }
        base.setGroupLabel("기본 근무");
        List<OvertimeGroupRule> rows = new ArrayList<>();
        rows.add(base);
        for (ShiftGroupDef group : hrMapper.findShiftGroups()) {
            if (Boolean.FALSE.equals(group.getActive())) {
                continue;
            }
            OvertimeGroupRule rule = saved.get(group.getCode());
            if (rule == null) {
                rule = new OvertimeGroupRule();
                rule.setGroupCode(group.getCode());
                rule.setExtendedAfterMinutes(base.getExtendedAfterMinutes());
                rule.setInclusiveMonthlyMinutes(base.getInclusiveMonthlyMinutes());
            }
            rule.setGroupLabel(group.getLabel());
            rows.add(rule);
        }
        return rows;
    }

    /** 조 코드에 적용되는 기준 — 저장된 행이 없으면 기본 근무 기준. groupCode 가 null 이면 기본 근무. */
    @Transactional(readOnly = true)
    public OvertimeGroupRule ruleFor(String groupCode) {
        List<OvertimeGroupRule> rules = groupRules();
        return rules.stream()
                .filter(rule -> rule.getGroupCode().equals(groupCode))
                .findFirst()
                .orElse(rules.get(0));
    }

    @Transactional
    public void saveGroupRules(CustomUserDetails currentUser, List<String> groupCodes,
                               List<BigDecimal> extendedAfterHours, List<BigDecimal> inclusiveMonthlyHours) {
        requireHrManager(currentUser);
        if (groupCodes == null || extendedAfterHours == null || inclusiveMonthlyHours == null
                || groupCodes.size() != extendedAfterHours.size() || groupCodes.size() != inclusiveMonthlyHours.size()) {
            throw new BusinessException("조별 기준 입력값이 올바르지 않습니다.");
        }
        Set<String> known = new java.util.HashSet<>();
        known.add(OvertimeGroupRule.DEFAULT_GROUP);
        hrMapper.findShiftGroups().forEach(group -> known.add(group.getCode()));
        int defaultIndex = groupCodes.indexOf(OvertimeGroupRule.DEFAULT_GROUP);
        Integer baseExtended = defaultIndex < 0 ? null
                : toMinutes("연장 기준 시간", extendedAfterHours.get(defaultIndex), 1, 24 * 60);
        Integer baseInclusive = defaultIndex < 0 ? null
                : toMinutes("포괄 인정 시간", inclusiveMonthlyHours.get(defaultIndex), 0, 31 * 24 * 60);
        for (int i = 0; i < groupCodes.size(); i++) {
            String code = groupCodes.get(i);
            if (!known.contains(code)) {
                throw new BusinessException("등록되지 않은 조입니다: " + code);
            }
            int extended = toMinutes("연장 기준 시간", extendedAfterHours.get(i), 1, 24 * 60);
            int inclusive = toMinutes("포괄 인정 시간", inclusiveMonthlyHours.get(i), 0, 31 * 24 * 60);
            // 기본 근무와 값이 같은 조는 따로 저장하지 않는다 — 나중에 기본 근무 기준을 바꾸면 함께 따라가야 한다.
            if (!OvertimeGroupRule.DEFAULT_GROUP.equals(code) && baseExtended != null
                    && baseExtended == extended && baseInclusive == inclusive) {
                overtimeMapper.deleteGroupRule(code);
                continue;
            }
            OvertimeGroupRule rule = new OvertimeGroupRule();
            rule.setGroupCode(code);
            rule.setExtendedAfterMinutes(extended);
            rule.setInclusiveMonthlyMinutes(inclusive);
            overtimeMapper.upsertGroupRule(rule);
        }
    }

    /* ── 조회 ─────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public OvertimeRequest request(Long id) {
        OvertimeRequest request = overtimeMapper.findRequest(id);
        if (request == null) {
            throw new BusinessException("시간외 근무 신청을 찾을 수 없습니다.");
        }
        return request;
    }

    @Transactional(readOnly = true)
    public List<OvertimeRequest> ownRequests(Long userId, YearMonth month) {
        return overtimeMapper.findRequests(userId, null, month.atDay(1), month.atEndOfMonth());
    }

    /** 인사담당자는 전원, 팀장·본부장은 산하만. 본인 건은 빼고 돌려준다(내 신청 목록에 따로 있다). */
    @Transactional(readOnly = true)
    public List<OvertimeRequest> memberRequests(CustomUserDetails currentUser, YearMonth month) {
        List<OvertimeRequest> rows;
        if (hrScopeService.canManageHr(currentUser)) {
            rows = overtimeMapper.findRequests(null, null, month.atDay(1), month.atEndOfMonth());
        } else {
            Set<Long> scope = hrScopeService.memberScopeUserIds(currentUser);
            if (scope.isEmpty()) {
                return List.of();
            }
            rows = overtimeMapper.findRequests(null, scope, month.atDay(1), month.atEndOfMonth());
        }
        return rows.stream().filter(row -> !row.getUserId().equals(currentUser.getUserId())).toList();
    }

    /* ── 신청·결재 ────────────────────────────────────── */

    /**
     * 본인 시간외 근무 신청.
     *
     * <p>종료 시각이 시작 시각보다 빠르거나 같으면 <b>다음 날</b>로 본다(자정을 넘긴 야근).
     * 끝난 시각이 이미 지났으면 사후 신청이다 — 근무한 달 안에서만 받는다.
     */
    @Transactional
    public OvertimeRequest requestOvertime(CustomUserDetails currentUser, LocalDate workDate,
                                           LocalTime startTime, LocalTime endTime, String reason) {
        if (workDate == null || startTime == null || endTime == null) {
            throw new BusinessException("근무일과 시작·종료 시각을 모두 입력하세요.");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("시간외 근무 사유를 입력하세요.");
        }
        LocalDateTime startAt = LocalDateTime.of(workDate, startTime);
        LocalDateTime endAt = LocalDateTime.of(endTime.isAfter(startTime) ? workDate : workDate.plusDays(1), endTime);
        int gross = OvertimeCalculator.minutesBetween(startAt, endAt);
        // 휴게는 입력받지 않는다 — 신청 길이로 자동 계산해 문서에 남기고, 정산 때는 실제 머문 길이로 다시 계산한다.
        int rest = OvertimeCalculator.autoBreakMinutes(gross);

        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        boolean post = !endAt.isAfter(now);
        if (post && !YearMonth.from(workDate).equals(YearMonth.from(now))) {
            throw new BusinessException("사후 신청은 근무한 달이 끝나기 전까지만 할 수 있습니다. ("
                    + workDate.getMonthValue() + "월 근무는 " + workDate.getMonthValue() + "월 말일까지)");
        }

        // 시간외는 정규 근무시간 밖의 시간이다 — 겹치면 같은 시간이 급여와 수당으로 두 번 잡힌다.
        // 반차를 쓴 반나절이나 종일 휴가일처럼 정규 근무가 없는 시간대는 그대로 신청할 수 있다.
        HrService.WorkWindow window = hrService.regularWorkWindow(currentUser.getUserId(), workDate);
        if (window != null && startAt.isBefore(window.end()) && endAt.isAfter(window.start())) {
            throw new BusinessException("기준 근무시간(" + window.start().toLocalTime() + "~" + window.end().toLocalTime()
                    + ")과 겹칩니다. 기준 근무시간 밖의 시간만 시간외 근무로 신청할 수 있습니다.");
        }

        OvertimeRequest conflict = overtimeMapper.findOverlapping(currentUser.getUserId(), startAt, endAt, null);
        if (conflict != null) {
            throw new BusinessException("같은 시간에 이미 신청된 시간외 근무가 있습니다 — "
                    + conflict.getWorkDate() + " " + conflict.getTimeText()
                    + "(" + conflict.getStatus().getLabel() + ").");
        }

        OvertimeSetting setting = setting();
        int night = OvertimeCalculator.nightMinutes(startAt, endAt, setting.getNightStart(), setting.getNightEnd());

        OvertimeRequest request = new OvertimeRequest();
        request.setUserId(currentUser.getUserId());
        request.setWorkDate(workDate);
        request.setStartAt(startAt);
        request.setEndAt(endAt);
        request.setBreakMinutes(rest);
        request.setRequestKind(post ? "POST" : "PRE");
        request.setDayType(dayType(workDate));
        request.setTotalMinutes(gross - rest);
        request.setNightMinutes(OvertimeCalculator.nightAfterBreak(gross, night, rest));
        request.setReason(reason.trim());
        request.setStatus(OvertimeStatus.PENDING);
        request.setCurrentApprovalStep(1);
        overtimeMapper.insertRequest(request);

        var steps = approvalService.createSteps(ApprovalDocumentType.OVERTIME, request.getId(),
                currentUser.getUserId(), workDate, endAt.toLocalDate());
        if (steps.isEmpty()) {
            throw new BusinessException("시간외 근무 결재경로가 설정되어 있지 않습니다.");
        }
        overtimeMapper.updateCurrentStep(request.getId(), steps.get(0).getStepOrder());
        return request;
    }

    /**
     * 이 신청을 더하면 그 주 근로시간이 한도(기본 52시간)를 넘는지 — 넘으면 안내 문구, 아니면 null.
     *
     * <p><b>막지 않는다.</b> 한도를 넘는 근무가 실제로 있었다면 기록에서 지우는 것이 아니라 드러나야 한다.
     * 신청자가 알고 올리도록 한 번 확인만 받는다. 주는 월요일~일요일이다.
     */
    @Transactional(readOnly = true)
    public String weekLimitWarning(Long userId, LocalDate workDate, LocalTime startTime, LocalTime endTime) {
        if (workDate == null || startTime == null || endTime == null) {
            return null;
        }
        LocalDateTime startAt = LocalDateTime.of(workDate, startTime);
        LocalDateTime endAt = LocalDateTime.of(endTime.isAfter(startTime) ? workDate : workDate.plusDays(1), endTime);
        int gross = Math.max(0, OvertimeCalculator.minutesBetween(startAt, endAt));
        int requested = gross - OvertimeCalculator.autoBreakMinutes(gross);
        LocalDate monday = workDate.minusDays(workDate.getDayOfWeek().getValue() - 1L);
        int already = overtimeMapper.findActiveRequests(userId, monday, monday.plusDays(6)).stream()
                .mapToInt(request -> request.getTotalMinutes() == null ? 0 : request.getTotalMinutes())
                .sum();
        int limitHours = setting().getWeeklyLimitHours();
        int total = STANDARD_WEEK_MINUTES + already + requested;
        if (total <= limitHours * 60) {
            return null;
        }
        return "이 신청을 더하면 " + monday.getMonthValue() + "/" + monday.getDayOfMonth() + " 주의 근로시간이 "
                + OvertimeRequest.minutesText(total) + "(소정 40시간 + 시간외 "
                + OvertimeRequest.minutesText(already + requested) + ")으로 주 " + limitHours + "시간을 넘습니다.";
    }

    @Transactional
    public void approve(CustomUserDetails currentUser, Long requestId) {
        OvertimeRequest request = request(requestId);
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 시간외 근무만 승인할 수 있습니다.");
        }
        approvalService.approvePendingStep(ApprovalDocumentType.OVERTIME, requestId, currentUser);
        var next = approvalService.pendingStep(ApprovalDocumentType.OVERTIME, requestId);
        if (next != null) {
            overtimeMapper.updateCurrentStep(requestId, next.getStepOrder());
            return;
        }
        overtimeMapper.updateStatus(requestId, OvertimeStatus.APPROVED.name(), currentUser.getUserId(), null);
    }

    @Transactional
    public void reject(CustomUserDetails currentUser, Long requestId, String reason) {
        OvertimeRequest request = request(requestId);
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 시간외 근무만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.OVERTIME, requestId, currentUser, reason);
        overtimeMapper.updateStatus(requestId, OvertimeStatus.REJECTED.name(), currentUser.getUserId(),
                StringUtils.hasText(reason) ? reason.trim() : null);
    }

    @Transactional
    public void cancel(CustomUserDetails currentUser, Long requestId) {
        OvertimeRequest request = request(requestId);
        if (!request.getUserId().equals(currentUser.getUserId()) && !hrScopeService.canManageHr(currentUser)) {
            throw new BusinessException("본인 시간외 근무 신청만 회수할 수 있습니다.");
        }
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 시간외 근무만 회수할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.OVERTIME, requestId);
        overtimeMapper.updateStatus(requestId, OvertimeStatus.CANCELED.name(), currentUser.getUserId(), null);
    }

    /* ── 내부 ─────────────────────────────────────────── */

    /** 그날이 평일인지 휴일·공휴일·근로자의 날인지 — 회사 휴무 달력과 요일별 근무시간표를 함께 본다. */
    @Transactional(readOnly = true)
    public OvertimeDayType dayType(LocalDate date) {
        CompanyHoliday holiday = hrMapper.findCompanyHoliday(date);
        boolean offWeekday = false;
        boolean configured = false;
        for (AttendanceWeekDay day : hrMapper.findWeekSchedule()) {
            if (day.getDayOfWeek() != null && day.getDayOfWeek() == date.getDayOfWeek().getValue()) {
                offWeekday = day.isOff();
                configured = true;
            }
        }
        if (!configured) {
            offWeekday = date.getDayOfWeek().getValue() >= 6; // 시간표 미설정 요일은 토·일 휴무
        }
        // 휴무 요일이라도 그날을 근무일로 지정한 일자 예외가 있으면 평일이다.
        boolean offDay = offWeekday && hrMapper.findAttendanceOverride(date) == null;
        return OvertimeCalculator.dayType(date, holiday, offDay);
    }

    private void requireHrManager(CustomUserDetails currentUser) {
        if (!hrScopeService.canManageHr(currentUser)) {
            throw new BusinessException("인사 관리자만 시간외 근무 설정을 바꿀 수 있습니다.");
        }
    }

    private static void requireRate(String name, BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_RATE) > 0) {
            throw new BusinessException(name + " 가산율은 0 이상 9.99 이하로 입력하세요.");
        }
    }

    private static void requireRange(String name, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new BusinessException(name + "은(는) " + min + "~" + max + " 사이로 입력하세요.");
        }
    }

    private static int toMinutes(String name, BigDecimal hours, int minMinutes, int maxMinutes) {
        if (hours == null) {
            throw new BusinessException(name + "을 입력하세요.");
        }
        int minutes = hours.multiply(BigDecimal.valueOf(60)).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
        if (minutes < minMinutes || minutes > maxMinutes) {
            throw new BusinessException(name + "이 허용 범위를 벗어났습니다.");
        }
        return minutes;
    }
}
