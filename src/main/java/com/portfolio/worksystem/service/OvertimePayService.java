package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.OvertimeMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.FlexTimeRequest;
import com.portfolio.worksystem.model.OvertimeGroupRule;
import com.portfolio.worksystem.model.OvertimeRequest;
import com.portfolio.worksystem.model.OvertimeSession;
import com.portfolio.worksystem.model.OvertimeSetting;
import com.portfolio.worksystem.model.OvertimeStatus;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [OT] 시간외 근무 월 집계 — 승인된 신청을 실제 출퇴근 기록과 맞춰 인정 시간과 수당을 낸다.
 *
 * <p>집계는 <b>저장하지 않고 조회할 때 계산한다.</b> 출퇴근 정정이나 뒤늦은 승인으로 기록이 바뀌면
 * 결과도 따라 바뀌어야 하기 때문이다. 급여로 넘길 값은 CSV 로 내려받은 시점의 것을 쓴다.
 *
 * <p>통상시급은 급여 관리의 <b>직급별 통상시급</b>(근무일에 유효한 값)이다.
 * 단가가 없는 등급은 금액을 0 으로 채우지 않고 비워 둔다(미설정과 0원은 다르다).
 *
 * <p>포괄 인정 시간은 급여에 이미 들어 있는 시간외이므로 <b>평일 연장 시간에서</b> 그만큼 빼고 지급 대상을 잡는다.
 * 야간 추가분과 휴일 근무는 빼지 않는다.
 */
@Service
@RequiredArgsConstructor
public class OvertimePayService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final OvertimeMapper overtimeMapper;
    private final OvertimeService overtimeService;
    private final HrMapper hrMapper;
    private final HrService hrService;
    private final UserMapper userMapper;
    private final HrScopeService hrScopeService;
    private final PayrollService payrollService;
    private final AdminPermissionService permissionService;

    /** 신청 한 건의 인정 내역. */
    public record Detail(OvertimeRequest request, OvertimePayCalculator.Line line, BigDecimal weightedMinutes) {
        public String getRecognizedText() {
            return OvertimeRequest.minutesText(line.recognizedMinutes());
        }
    }

    /** 직원 한 명의 월 집계. 분 단위 값은 화면·CSV 에서 시간으로 바꿔 보인다. */
    public record Row(Long userId, String memberName, String teamName, String groupLabel, int requestCount,
                      int recognizedMinutes, int withinStandardMinutes, int extendedMinutes,
                      int holidayMinutes, int holidayOverMinutes, int nightMinutes,
                      int inclusiveDeductedMinutes, BigDecimal weightedMinutes,
                      BigDecimal hourlyWage, BigDecimal amount, List<Detail> details) {

        public String text(int minutes) {
            return OvertimeRequest.minutesText(minutes);
        }

        /** 지급 대상 연장 시간(포괄 인정분을 뺀 것). */
        public int getPayableExtendedMinutes() {
            return Math.max(0, extendedMinutes - inclusiveDeductedMinutes);
        }

        public BigDecimal getWeightedHours() {
            return weightedMinutes.divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
        }
    }

    /** [PAY] 수당 금액·통상시급을 볼 수 있는지 — 급여 관리 권한자만. 다른 사람은 시간만 본다. */
    public boolean canSeeAmounts(CustomUserDetails currentUser) {
        return permissionService.can(currentUser, com.portfolio.worksystem.model.AdminMenu.PAYROLL);
    }

    /**
     * 월 집계. 인사 관리자·급여 관리 권한자는 승인 건이 있는 전 직원, 조직장은 산하와 본인, 그 밖에는 본인만 본다.
     */
    @Transactional(readOnly = true)
    public List<Row> monthly(CustomUserDetails currentUser, YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        Collection<Long> scope = null;
        if (!hrScopeService.canManageHr(currentUser) && !canSeeAmounts(currentUser)) {
            Set<Long> ids = new java.util.HashSet<>(hrScopeService.memberScopeUserIds(currentUser));
            ids.add(currentUser.getUserId());
            scope = ids;
        }
        Map<Long, List<OvertimeRequest>> byUser = new LinkedHashMap<>();
        for (OvertimeRequest request : overtimeMapper.findRequests(null, scope, from, to)) {
            if (request.getStatus() == OvertimeStatus.APPROVED) {
                byUser.computeIfAbsent(request.getUserId(), id -> new ArrayList<>()).add(request);
            }
        }
        if (byUser.isEmpty()) {
            return List.of();
        }
        OvertimeSetting setting = overtimeService.setting();
        List<OvertimeGroupRule> rules = overtimeService.groupRules();
        // [PAY] 통상시급은 급여 관리의 직급별 값 — 근무일에 유효한 값을 쓴다(월중 인상도 날짜대로).
        Map<LocalDate, Map<Long, PayrollService.Resolved>> wagesByDay = new HashMap<>();
        java.util.function.BiFunction<Long, LocalDate, BigDecimal> wageOf = (gradeId, day) -> {
            if (gradeId == null || day == null) {
                return null;
            }
            PayrollService.Resolved resolved = wagesByDay.computeIfAbsent(day, payrollService::wagesAt).get(gradeId);
            return resolved == null ? null : resolved.hourlyWage();
        };
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Long, List<OvertimeRequest>> entry : byUser.entrySet()) {
            rows.add(rowFor(entry.getKey(), entry.getValue(), from, to, setting, rules, wageOf));
        }
        rows.sort(Comparator.comparing((Row row) -> row.teamName() == null ? "" : row.teamName())
                .thenComparing(row -> row.memberName() == null ? "" : row.memberName()));
        return rows;
    }

    private Row rowFor(Long userId, List<OvertimeRequest> requests, LocalDate from, LocalDate to,
                       OvertimeSetting setting, List<OvertimeGroupRule> rules,
                       java.util.function.BiFunction<Long, LocalDate, BigDecimal> wageOf) {
        UserAccount user = userMapper.findById(userId);
        Map<LocalDate, AttendanceRecord> records = new HashMap<>();
        for (AttendanceRecord record : hrMapper.findAttendanceRecords(userId, null, from, to.plusDays(1))) {
            records.put(record.getWorkDate(), record);
        }
        // 자정을 넘긴 근무가 있어 앞뒤로 하루씩 넓혀 가져온다.
        List<OvertimeSession> sessions = overtimeMapper.findSessions(userId, from.minusDays(1), to.plusDays(1));

        List<Detail> details = new ArrayList<>();
        int recognized = 0, within = 0, extended = 0, holiday = 0, holidayOver = 0, night = 0;
        BigDecimal weighted = BigDecimal.ZERO;
        requests.sort(Comparator.comparing(OvertimeRequest::getStartAt));
        for (OvertimeRequest request : requests) {
            LocalDate day = request.getWorkDate();
            AttendanceRecord record = records.get(day);
            List<OvertimePayCalculator.Presence> presences = new ArrayList<>();
            boolean anyRecord = false;
            int regularWorked = 0;
            if (record != null && record.getCheckInAt() != null) {
                anyRecord = true;
                if (record.getCheckOutAt() != null) {
                    presences.add(new OvertimePayCalculator.Presence(record.getCheckInAt(), record.getCheckOutAt()));
                    HrService.WorkSchedule schedule = hrService.workSchedule(userId, day);
                    regularWorked = OvertimePayCalculator.regularWorkedMinutes(record.getCheckInAt(), record.getCheckOutAt(),
                            LocalDateTime.of(day, schedule.startTime()), LocalDateTime.of(day, schedule.endTime()));
                }
            }
            for (OvertimeSession session : sessions) {
                boolean sameDay = day.equals(session.getWorkDate());
                boolean overlaps = session.getCheckOutAt() != null
                        && session.getCheckInAt().isBefore(request.getEndAt())
                        && session.getCheckOutAt().isAfter(request.getStartAt());
                if (sameDay || overlaps) {
                    anyRecord = true;
                }
                if (session.getCheckOutAt() != null) {
                    presences.add(new OvertimePayCalculator.Presence(session.getCheckInAt(), session.getCheckOutAt()));
                }
            }
            OvertimeGroupRule rule = ruleOn(userId, day, rules);
            // 그날 출퇴근 기록이 아예 없으면 정규 근무를 다 채운 것으로 보고 신청 시간 전부를 연장으로 잡는다.
            int regularForThreshold = anyRecord ? regularWorked : rule.getExtendedAfterMinutes();
            // 신청 단계에서 막지만, 승인 뒤 근무시간표·시차출근이 바뀐 건도 있어 정산에서 한 번 더 걸러 낸다.
            HrService.WorkWindow window = hrService.regularWorkWindow(userId, day);
            OvertimePayCalculator.Presence regularWindow = window == null ? null
                    : new OvertimePayCalculator.Presence(window.start(), window.end());
            OvertimePayCalculator.Line line = OvertimePayCalculator.recognize(request.getStartAt(), request.getEndAt(),
                    request.getDayType(),
                    presences, anyRecord, regularWindow, regularForThreshold, rule.getExtendedAfterMinutes(),
                    setting.getNightStart(), setting.getNightEnd(), setting.getHolidayOverHours());
            BigDecimal lineWeighted = OvertimePayCalculator.weightedMinutes(line, request.getDayType(), setting);
            details.add(new Detail(request, line, lineWeighted));
            recognized += line.recognizedMinutes();
            within += line.withinStandardMinutes();
            extended += line.extendedMinutes();
            holiday += line.holidayMinutes();
            holidayOver += line.holidayOverMinutes();
            night += line.nightMinutes();
            weighted = weighted.add(lineWeighted);
        }
        // 포괄 인정 — 달의 마지막 날(이번 달이면 오늘) 기준 조의 값을 그 달 전체에 쓴다.
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        OvertimeGroupRule monthRule = ruleOn(userId, to.isAfter(today) ? today : to, rules);
        int deducted = Math.min(extended, Math.max(0, monthRule.getInclusiveMonthlyMinutes()));
        BigDecimal deductedWeighted = setting.getRateExtended().multiply(BigDecimal.valueOf(deducted));
        weighted = weighted.subtract(deductedWeighted);

        // 금액은 건마다 그 근무일의 통상시급으로 계산해 더한다. 포괄 차감은 그 달 마지막 근무일 시급으로 뺀다.
        // 한 건이라도 시급이 없으면 금액을 내지 않는다(0원이 아니라 '미설정').
        Long gradeId = user == null ? null : user.getGradeId();
        LocalDate lastDay = details.isEmpty() ? to : details.get(details.size() - 1).request().getWorkDate();
        BigDecimal hourly = wageOf.apply(gradeId, lastDay);
        BigDecimal amount = BigDecimal.ZERO;
        for (Detail detail : details) {
            BigDecimal dayWage = wageOf.apply(gradeId, detail.request().getWorkDate());
            BigDecimal lineAmount = OvertimePayCalculator.amount(dayWage, detail.weightedMinutes());
            if (lineAmount == null) {
                amount = null;
                break;
            }
            amount = amount.add(lineAmount);
        }
        if (amount != null) {
            BigDecimal deduction = OvertimePayCalculator.amount(hourly, deductedWeighted);
            amount = deduction == null ? null : amount.subtract(deduction).max(BigDecimal.ZERO);
        }
        return new Row(userId, user == null ? "-" : user.getName(), user == null ? null : user.getTeamName(),
                monthRule.getGroupLabel(), requests.size(), recognized, within, extended, holiday, holidayOver, night,
                deducted, weighted, hourly, amount, details);
    }

    /** 그날 적용되는 조별 기준 — 승인된 시차출근이 있으면 그 조, 없으면 기본 근무. */
    private OvertimeGroupRule ruleOn(Long userId, LocalDate date, List<OvertimeGroupRule> rules) {
        FlexTimeRequest flex = hrMapper.findApprovedFlexTimeForDate(userId, date);
        String code = flex == null ? null : flex.getShiftGroup();
        return rules.stream().filter(rule -> rule.getGroupCode().equals(code)).findFirst().orElse(rules.get(0));
    }
}
