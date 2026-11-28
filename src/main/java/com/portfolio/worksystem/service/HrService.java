package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.AttendanceCorrectionForm;
import com.portfolio.worksystem.dto.AttendanceOverrideForm;
import com.portfolio.worksystem.dto.AttendanceSettingForm;
import com.portfolio.worksystem.dto.BusinessTripExpenseForm;
import com.portfolio.worksystem.dto.BusinessTripForm;
import com.portfolio.worksystem.dto.CompanyScheduleForm;
import com.portfolio.worksystem.dto.FlexTimeRequestForm;
import com.portfolio.worksystem.dto.HolidayForm;
import com.portfolio.worksystem.dto.LeaveRequestForm;
import com.portfolio.worksystem.mapper.ApprovalMapper;
import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.ApprovalDocumentSnapshot;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.ApprovalInboxItem;
import com.portfolio.worksystem.model.ApprovalStep;
import com.portfolio.worksystem.model.AttendanceCorrection;
import com.portfolio.worksystem.model.AttendanceCorrectionStatus;
import com.portfolio.worksystem.model.AttendanceDailyStatus;
import com.portfolio.worksystem.model.AttendanceDayOverride;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.AttendanceSetting;
import com.portfolio.worksystem.model.AttendanceWeekDay;
import com.portfolio.worksystem.model.AttendanceStatus;
import com.portfolio.worksystem.model.BusinessTrip;
import com.portfolio.worksystem.model.BusinessTripExpense;
import com.portfolio.worksystem.model.BusinessTripExpenseType;
import com.portfolio.worksystem.model.BusinessTripReceipt;
import com.portfolio.worksystem.model.BusinessTripStatus;
import com.portfolio.worksystem.model.CompanyHoliday;
import com.portfolio.worksystem.model.CompanySchedule;
import com.portfolio.worksystem.model.FlexTimeRequest;
import com.portfolio.worksystem.model.FlexTimeStatus;
import com.portfolio.worksystem.model.LeaveBalance;
import com.portfolio.worksystem.model.LeaveRequest;
import com.portfolio.worksystem.model.LeaveStatus;
import com.portfolio.worksystem.model.ShiftGroupDef;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.Period;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
@Service
public class HrService {
    private static final BigDecimal DEFAULT_ANNUAL_DAYS = new BigDecimal("15.00");
    /** 관리자가 넣을 수 있는 부여 상한의 상한 — 오타로 세 자리를 넣는 것을 막는 정도의 방어선. */
    private static final int MAX_ENTITLEMENT_CAP = 40;
    private static final long MAX_TRIP_RECEIPT_BYTES = 10L * 1024L * 1024L;
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalTime FALLBACK_START = LocalTime.of(9, 0);
    private static final LocalTime FALLBACK_END = LocalTime.of(18, 0);
    /** 반차 판정 보정폭 — 기준 근무시간(점심 포함 9시간)의 반일에 해당하는 4시간(09~18시 기준 오전반차 13:00 출근·오후반차 14:00 퇴근). */
    private static final long HALF_DAY_HOURS = 4;

    private final HrMapper hrMapper;
    private final com.portfolio.worksystem.mapper.TripReportMapper tripReportMapper;
    private final UserMapper userMapper;
    private final ApprovalService approvalService;
    private final CommunicationService communicationService;
    private final PanelRefreshPublisher panelRefreshPublisher;
    /** 누가 누구의 인사 정보를 볼 수 있는가 — 조직도·팀 조회는 전부 이쪽으로 옮겼다. */
    private final HrScopeService hrScopeService;
    /** 결재문서 열람·보관(HTML 렌더링·서명 스냅샷). */
    private final ApprovalDocumentService approvalDocumentService;
    /** [D4] 연차수당 단가(연도·등급별) — 미사용 연차는 이월하지 않고 수당으로 나간다. */
    private final LeaveAllowanceService leaveAllowanceService;
    /** [B7] 휴직 — 연차 출근율 반영에 쓴다. */
    private final LoaService loaService;

    @Value("${app.upload.trip-receipt-dir:uploads/trip-receipts}")
    private String tripReceiptDir;

    public int currentYear() {
        return Year.now(SERVICE_ZONE).getValue();
    }


    /* ── 권한 범위 — 실제 판정은 HrScopeService. 화면·다른 서비스가 오래 쓰던 이름이라 여기서 넘겨준다. ── */

    public boolean canManageHr(CustomUserDetails currentUser) {
        return hrScopeService.canManageHr(currentUser);
    }

    public boolean canReviewPeople(CustomUserDetails currentUser) {
        return hrScopeService.canReviewPeople(currentUser);
    }

    public boolean canViewMemberHrOverview(CustomUserDetails currentUser) {
        return hrScopeService.canViewMemberHrOverview(currentUser);
    }

    public boolean canViewMemberHrCard(CustomUserDetails currentUser, Long targetUserId) {
        return hrScopeService.canViewMemberHrCard(currentUser, targetUserId);
    }

    public String memberHrScopeLabel(CustomUserDetails currentUser) {
        return hrScopeService.memberHrScopeLabel(currentUser);
    }

    @Transactional
    public LeaveBalance leaveBalance(CustomUserDetails currentUser, Integer year) {
        int targetYear = year == null ? currentYear() : year;
        UserAccount user = userMapper.findById(currentUser.getUserId());
        BigDecimal entitlement = annualLeaveEntitlement(user == null ? null : user.getHireDate(), targetYear,
                loaService.excludedLoaDatesByUser(targetYear).get(currentUser.getUserId()),
                user == null ? null : user.getResignedOn());
        LeaveBalance balance = hrMapper.findLeaveBalance(currentUser.getUserId(), targetYear);
        if (balance != null) {
            balance.setTotalDays(entitlement);
            balance.setNote("입사일 기준 자동 산정");
            hrMapper.upsertLeaveBalance(balance);
            return balance;
        }
        balance = new LeaveBalance();
        balance.setUserId(currentUser.getUserId());
        balance.setBalanceYear(targetYear);
        balance.setTotalDays(entitlement);
        balance.setUsedDays(BigDecimal.ZERO);
        balance.setAdjustedDays(BigDecimal.ZERO);
        balance.setNote("입사일 기준 자동 산정");
        hrMapper.insertLeaveBalance(balance);
        return balance;
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> leaveRequests(CustomUserDetails currentUser, Integer year) {
        var visibility = visibility(currentUser);
        return hrMapper.findLeaveRequests(visibility.userId(), visibility.teamId(), year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> allLeaveRequests(Integer year) {
        return hrMapper.findLeaveRequests(null, null, year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public UserAccount findUser(Long userId) {
        UserAccount user = userMapper.findById(userId);
        if (user != null) {
            // [UX4] 인사카드 권한 표시용
            user.setRoles(userMapper.findRolesByUserId(userId));
        }
        return user;
    }

    /** [P-6] 주간보고 검색 범위 — 본부장/팀장이면 산하 모든 직원 + 본인. */
    public java.util.Set<Long> reportSearchScope(CustomUserDetails currentUser) {
        return hrScopeService.reportSearchScope(currentUser);
    }

    // [O-2~O-5] 결재 페이지에서 본인 신청 내역만 조회할 때 사용
    @Transactional(readOnly = true)
    public List<LeaveRequest> ownLeaveRequests(Long userId, Integer year) {
        return hrMapper.findLeaveRequests(userId, null, year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public List<FlexTimeRequest> ownFlexTimeRequests(Long userId, Integer year) {
        return hrMapper.findFlexTimeRequests(userId, null, year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public List<BusinessTrip> ownBusinessTrips(Long userId, Integer year) {
        return hrMapper.findBusinessTrips(userId, null, year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public List<AttendanceCorrection> ownAttendanceCorrections(Long userId, Integer year) {
        return hrMapper.findAttendanceCorrections(userId, null, year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> memberLeaveRequests(CustomUserDetails currentUser, Integer year) {
        Set<Long> userIds = memberScopeUserIds(currentUser);
        if (userIds.isEmpty()) {
            return List.of();
        }
        return hrMapper.findLeaveRequests(null, null, year == null ? currentYear() : year).stream()
                .filter(request -> userIds.contains(request.getUserId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FlexTimeRequest> memberFlexTimeRequests(CustomUserDetails currentUser, Integer year) {
        Set<Long> userIds = memberScopeUserIds(currentUser);
        if (userIds.isEmpty()) return List.of();
        return hrMapper.findFlexTimeRequests(null, null, year == null ? currentYear() : year).stream()
                .filter(r -> userIds.contains(r.getUserId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BusinessTrip> memberTripRequests(CustomUserDetails currentUser, Integer year) {
        Set<Long> userIds = memberScopeUserIds(currentUser);
        if (userIds.isEmpty()) return List.of();
        return hrMapper.findBusinessTrips(null, null, year == null ? currentYear() : year).stream()
                .filter(t -> userIds.contains(t.getUserId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public LeaveEntitlementSummary leaveEntitlementSummary(CustomUserDetails currentUser, Integer year) {
        int targetYear = year == null ? currentYear() : year;
        UserAccount user = userMapper.findById(currentUser.getUserId());
        LocalDate hireDate = user == null ? null : user.getHireDate();
        java.util.Set<LocalDate> loaDates = loaService.excludedLoaDatesByUser(targetYear)
                .get(currentUser.getUserId());
        BigDecimal entitlement = annualLeaveEntitlement(hireDate, targetYear, loaDates,
                user == null ? null : user.getResignedOn());
        if (hireDate == null) {
            return new LeaveEntitlementSummary("-", "입사일 미등록", formatDays(entitlement));
        }
        LocalDate referenceDate = referenceDate(targetYear);
        if (hireDate.isAfter(referenceDate)) {
            return new LeaveEntitlementSummary("근무 전", "0", "0");
        }
        Period period = Period.between(hireDate, referenceDate);
        String tenureText = "%d년 %02d월 %02d일 근무중".formatted(period.getYears(), period.getMonths(), period.getDays());
        if (period.getYears() < 1) {
            return new LeaveEntitlementSummary(tenureText, formatDays(entitlement), formatDays(entitlement));
        }
        return new LeaveEntitlementSummary(tenureText, entitlementExpression(entitlement), formatDays(entitlement));
    }

    /**
     * 화면에 뿌릴 산식 문구 — <b>부여일수에서 되짚어</b> 만든다.
     *
     * <p>예전에는 근속연수로 가산일수를 다시 계산해 "15+N" 을 만들었다. 그래서 부여 기준이
     * 회계연도(1/1 확정)로 바뀌면 문구는 여전히 입사일 기준(12/31)으로 계산돼
     * <b>"20(15+5)" 라고 써놓고 19일을 주는</b> 어긋남이 생겼다. 상한(V81)까지 들어오면
     * 어긋날 경우의 수가 더 늘어난다.
     *
     * <p>실제로 원장에 들어가는 값 하나에서 문구를 파생시키면 이 어긋남 자체가 생길 수 없다.
     */
    private String entitlementExpression(BigDecimal entitlement) {
        Integer cap = leaveAccrualPolicy().getMaxAnnualDays();
        String days = formatDays(entitlement);
        if (cap != null && entitlement.compareTo(BigDecimal.valueOf(cap)) >= 0) {
            return days + "(상한)";
        }
        int extraDays = Math.max(0, entitlement.subtract(DEFAULT_ANNUAL_DAYS).intValue());
        return extraDays > 0 ? days + "(15+" + extraDays + ")" : days;
    }

    @Transactional
    public void requestLeave(CustomUserDetails currentUser, LeaveRequestForm form) {
        requestLeave(currentUser, form, null);
    }

    /** [DLG] 휴가 신청 — 결재자라면 부재 중 대리승인자를 함께 '예정'으로 지정한다. */
    @Transactional
    public void requestLeave(CustomUserDetails currentUser, LeaveRequestForm form, Long delegateUserId) {
        // [B6] 휴직 중 휴가 신청 차단
        UserAccount requester = userMapper.findById(currentUser.getUserId());
        if (requester != null && requester.isOnLeave(LocalDate.now(SERVICE_ZONE))) {
            throw new BusinessException("휴직 중에는 휴가를 신청할 수 없습니다. 복직 후 신청하세요.");
        }
        if (form.type() == null) {
            throw new BusinessException("휴가 종류를 선택하세요.");
        }
        if (form.startDate() == null || form.endDate() == null) {
            throw new BusinessException("휴가 시작일과 종료일을 입력하세요.");
        }
        if (form.startDate().isAfter(form.endDate())) {
            throw new BusinessException("휴가 종료일은 시작일보다 빠를 수 없습니다.");
        }
        if (form.startDate().getYear() != form.endDate().getYear()) {
            throw new BusinessException(crossYearMessage(form.startDate(), form.endDate()));
        }
        // [C2] 사용 중지된 휴가 종류는 신규 신청 차단
        if (!Boolean.TRUE.equals(leaveTypeDef(form.type()).getActive())) {
            throw new BusinessException("사용 중지된 휴가 종류입니다. 다른 종류를 선택하세요.");
        }
        LeaveRequest conflict = conflictingLeave(currentUser.getUserId(), form.type(),
                form.startDate(), form.endDate(), null);
        if (conflict != null) {
            throw new BusinessException(overlapMessage(conflict));
        }
        requireApprovalRoute(currentUser, ApprovalDocumentType.LEAVE, form.startDate(), form.endDate());
        BigDecimal days = calculateLeaveDays(form.type(), form.startDate(), form.endDate());
        if (days.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("근무일 기준으로 신청 가능한 휴가일이 없습니다.");
        }
        boolean deductAnnual = isAnnualCountedType(form.type());
        if (deductAnnual) {
            LeaveBalance balance = leaveBalance(currentUser, form.startDate().getYear());
            if (balance.getRemainingDays().compareTo(days) < 0) {
                throw new BusinessException("잔여 연차가 부족합니다.");
            }
        }
        LeaveRequest request = new LeaveRequest();
        request.setUserId(currentUser.getUserId());
        request.setType(form.type());
        request.setStartDate(form.startDate());
        request.setEndDate(form.endDate());
        request.setDays(days);
        request.setAnnualDeductedDays(deductAnnual ? days : BigDecimal.ZERO);
        request.setReason(trimToNull(form.reason()));
        request.setStatus(LeaveStatus.PENDING);
        request.setCurrentApprovalStep(1);
        hrMapper.insertLeaveRequest(request);
        // 신청 즉시 잔여 차감 (PENDING 포함). 회수/반려 시 복구.
        if (request.getAnnualDeductedDays().signum() > 0) {
            hrMapper.increaseUsedLeave(currentUser.getUserId(), form.startDate().getYear(), request.getAnnualDeductedDays());
        }
        List<ApprovalStep> steps = approvalService.createSteps(ApprovalDocumentType.LEAVE, request.getId(), currentUser.getUserId(), form.startDate(), form.endDate());
        if (steps.isEmpty()) {
            throw new BusinessException("연차 결재경로가 설정되어 있지 않습니다.");
        }
        hrMapper.updateLeaveCurrentStep(request.getId(), steps.get(0).getStepOrder());
        approvalService.attachDelegation(currentUser.getUserId(), delegateUserId, form.startDate(), form.endDate(),
                "휴가", ApprovalDocumentType.LEAVE, request.getId());
        afterSubmit(steps, currentUser, ApprovalDocumentType.LEAVE, request.getId(),
                () -> approveLeave(currentUser, request.getId()));
    }

    /** 휴가 신청 폼의 차감 예정일수 실시간 미리보기 — 계산 규칙은 신청 검증(calculateLeaveDays)과 동일. */
    /**
     * @param year        <b>신청 날짜의 연도</b>. 연차는 연도별로 부여·집계되므로 화면이 어느 해
     *                    기준으로 말하고 있는지 같이 내려 줘야 한다 — 올해 잔여를 띄워 놓고
     *                    내년 날짜의 계산을 보여 주면 두 숫자가 서로 다른 해를 가리킨다.
     * @param entitlement 그 해 <b>총 부여일수</b>(연말 기준).
     * @param accrualNote 입사기념일 전후로 총 부여가 달라지는 사람에게만 주는 안내. 없으면 null.
     */
    public record LeaveDaysPreview(BigDecimal days, boolean annualCounted,
                                   BigDecimal remaining, BigDecimal remainingAfter, List<String> warnings,
                                   Integer year, BigDecimal entitlement, String accrualNote) {
    }

    @Transactional(readOnly = true)
    public LeaveDaysPreview leaveDaysPreview(CustomUserDetails currentUser, String type,
                                             LocalDate startDate, LocalDate endDate) {
        if (type == null || type.isBlank() || startDate == null || endDate == null) {
            return new LeaveDaysPreview(BigDecimal.ZERO, false, null, null, List.of(), null, null, null);
        }
        if (startDate.isAfter(endDate)) {
            return new LeaveDaysPreview(BigDecimal.ZERO, false, null, null,
                    List.of("휴가 종료일은 시작일보다 빠를 수 없습니다."), null, null, null);
        }
        int year = startDate.getYear();
        if (startDate.getYear() != endDate.getYear()) {
            return new LeaveDaysPreview(BigDecimal.ZERO, false, null, null,
                    List.of(crossYearMessage(startDate, endDate)), year, null, null);
        }
        BigDecimal days;
        try {
            days = calculateLeaveDays(type, startDate, endDate);
        } catch (BusinessException e) {
            return new LeaveDaysPreview(BigDecimal.ZERO, false, null, null, List.of(e.getMessage()), year, null, null);
        }
        // 휴가 종류를 넘겨야 겹침 판정이 신청 검증과 같아진다 — 오전+오후 반차는 겹침이 아니다.
        List<String> warnings = new ArrayList<>(
                dateValidationWarnings(currentUser, ApprovalDocumentType.LEAVE, startDate, endDate, type));
        if (days.compareTo(BigDecimal.ZERO) <= 0) {
            warnings.add("근무일 기준으로 신청 가능한 휴가일이 없습니다.");
        }
        boolean counted = isAnnualCountedType(type);
        BigDecimal remaining = null;
        BigDecimal remainingAfter = null;
        // 잔여·부여는 <신청 날짜의 연도> 기준이다. 내년 날짜로 신청하면 내년 원장을 본다.
        LeaveBalance balance = leaveBalance(currentUser, year);
        if (counted) {
            remaining = balance.getRemainingDays();
            remainingAfter = remaining.subtract(days);
            if (days.compareTo(BigDecimal.ZERO) > 0 && remainingAfter.compareTo(BigDecimal.ZERO) < 0) {
                warnings.add("잔여 연차가 부족합니다.");
            }
        }
        UserAccount requester = userMapper.findById(currentUser.getUserId());
        return new LeaveDaysPreview(days, counted, remaining, remainingAfter, warnings,
                year, balance.getTotalDays(),
                accrualNote(requester == null ? null : requester.getHireDate(), year, startDate,
                        loaService.excludedLoaDatesByUser(year).get(currentUser.getUserId())));
    }

    /**
     * 그 해 <b>총 부여</b>로 계산하되, 입사기념일 전이라 아직 그만큼 발생하지 않은 사람에게 알려 준다.
     *
     * <p>입사일 기준(HIRE_DATE)에서는 입사기념일이 지나야 가산휴가가 붙는다. 화면은 그 해
     * 총 부여(연말 기준)로 계산하므로 — 미리 당겨 쓰는 것을 허용하는 방침이다 — 기념일 전에
     * 신청하는 사람은 <b>아직 발생하지 않은 몫까지 포함된 숫자</b>를 보게 된다. 숨기면 나중에
     * "왜 그때는 21일이라더니" 가 되므로 그 자리에서 말해 준다.
     *
     * <p>회계연도 기준(FISCAL_YEAR)이 적용되는 해는 1/1 에 부여가 확정되어 연중 변하지 않으므로
     * 안내할 것이 없다.
     */
    private String accrualNote(LocalDate hireDate, int year, LocalDate startDate,
                               java.util.Set<LocalDate> excludedLoaDates) {
        if (hireDate == null) {
            return null;
        }
        com.portfolio.worksystem.model.LeaveAccrualPolicy policy = leaveAccrualPolicy();
        if (policy.isFiscalYear() && policy.appliesTo(year)) {
            return null;
        }
        // 양쪽 다 휴직 반영분을 태워야 한다 — 한쪽만 태우면 화면 숫자와 안내가 어긋난다.
        BigDecimal atYearEnd = annualLeaveEntitlement(hireDate, year, excludedLoaDates);
        BigDecimal atRequest = prorateForLeaveOfAbsence(
                entitlementAsOf(hireDate, startDate), year, excludedLoaDates);
        if (atRequest.compareTo(atYearEnd) >= 0) {
            return null;
        }
        LocalDate anniversary = hireDate.plusYears(year - hireDate.getYear());
        return year + "년 총 부여 " + formatDays(atYearEnd) + "일 기준으로 계산합니다."
                + " 입사기념일(" + anniversary + ") 전까지 발생분은 " + formatDays(atRequest) + "일입니다.";
    }

    /**
     * 해를 걸친 신청은 받지 않는다.
     *
     * <p>연차는 연도별로 부여되고 연도별 원장(leave_balances.balance_year)에서 차감되며,
     * 조회·집계·수당 정산이 모두 {@code YEAR(start_date)} 로 묶여 있다. 한 건이 두 해에 걸치면
     * 어느 해에 얼마를 차감할지가 한 건 안에서 갈리는데, 지금 구조는 그것을 표현하지 못해
     * <b>전부 시작 연도에서 깎였다</b> — 1/1 이후 쓴 날까지 작년 것에서 나갔다.
     *
     * <p>두 건으로 나누면 각자 그 해 잔여에서 정확히 차감되고 결재도 따로 남는다.
     */
    private String crossYearMessage(LocalDate startDate, LocalDate endDate) {
        LocalDate yearEnd = LocalDate.of(startDate.getYear(), 12, 31);
        return "연차는 연도별로 부여·집계되므로 해를 걸쳐 한 번에 신청할 수 없습니다."
                + " " + startDate + " ~ " + yearEnd + " 와 "
                + yearEnd.plusDays(1) + " ~ " + endDate + " 로 나눠 신청하세요.";
    }

    private String docPath(ApprovalDocumentType type, Long id) {
        return switch (type) {
            case LEAVE, LEAVE_CANCEL -> "/hr/leave/" + id + "/document";
            case TRIP -> "/hr/trips/" + id + "/document";
            case TRIP_REPORT -> "/hr/trips/report/" + id + "/document";
            case FLEX_TIME -> "/hr/flex-time/" + id + "/document";
            case ATTENDANCE_CORRECTION -> "/hr/attendance/correction/" + id + "/document";
            case LOA -> "/hr/loa/" + id + "/document";
            case OVERTIME -> "/hr/overtime/" + id + "/document";
            case OUTSIDE_WORK -> "/hr/outside-work/" + id + "/document";
        };
    }

    // 1차 단계의 결재자가 기안자 본인(대리자 없음)인지.
    private boolean firstStepIsRequester(List<ApprovalStep> steps, Long requesterId) {
        return steps != null && !steps.isEmpty()
                && steps.get(0).getDelegateUserId() == null
                && java.util.Objects.equals(steps.get(0).getApproverUserId(), requesterId);
    }

    // 제출 처리: 1차가 기안자 본인이면 본인 단계를 자동 통과시켜 '바로 다음 결재자'에게 넘긴다.
    // 그렇지 않으면 1차 결재자에게 결재 요청 알림을 보낸다.
    private void afterSubmit(List<ApprovalStep> steps, CustomUserDetails currentUser,
                             ApprovalDocumentType type, Long docId, Runnable approveOnce) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        if (firstStepIsRequester(steps, currentUser.getUserId())) {
            autoPassOwnSteps(type, docId, currentUser.getUserId(), approveOnce);
        } else {
            notifyTurn(steps.get(0), currentUser.getUserId(), type, docId, currentUser.getName(), currentUser.getUserId());
        }
    }

    // 현재 대기 단계의 결재자가 기안자 본인인 동안 자동 승인하여 다음(타인) 결재자 또는 완료까지 진행.
    // approve* 가 다음 결재자 알림/완료 알림을 처리하므로 여기서는 진행만 시킨다.
    private void autoPassOwnSteps(ApprovalDocumentType type, Long docId, Long requesterId, Runnable approveOnce) {
        for (int guard = 0; guard < 20; guard++) {
            ApprovalStep pending = approvalService.pendingStep(type, docId);
            if (pending == null) {
                return; // 전결 완료
            }
            boolean mine = pending.getDelegateUserId() == null
                    && java.util.Objects.equals(pending.getApproverUserId(), requesterId);
            if (!mine) {
                return; // 기안자 바로 다음 결재자(타인) 차례 → 그 사람 결재 대기로 남김
            }
            approveOnce.run();
        }
    }

    private void notifyTurn(ApprovalStep step, Long senderId, ApprovalDocumentType type,
                            Long docId, String requesterName, Long requesterUserId) {
        if (step == null) {
            return;
        }
        String link = docPath(type, docId);
        String docTitle = composeDocumentTitle(type, requesterUserId, requesterName);
        String body = "결재 요청이 도착했습니다.";
        communicationService.notifyUser(step.getApproverUserId(), senderId, "APPROVAL_REQUEST", docTitle, body, link);
        if (step.getDelegateUserId() != null) {
            communicationService.notifyUser(step.getDelegateUserId(), senderId, "APPROVAL_REQUEST", docTitle, body, link);
        }
    }

    private void notifyDecided(Long requesterId, Long senderId, ApprovalDocumentType type, Long docId,
                               boolean approved, String reason) {
        String link = docPath(type, docId);
        String docTitle = composeDocumentTitle(type, requesterId, null);
        String body = (approved ? "결재가 완료(승인)되었습니다." : "결재가 반려되었습니다.")
                + (!approved && reason != null && !reason.isBlank() ? " 사유: " + reason : "");
        communicationService.notifyUser(requesterId, senderId,
                approved ? "APPROVAL_DONE" : "APPROVAL_REJECTED", docTitle, body, link);
    }

    private void notifyPostApprovalNeeded(ApprovalStep step, Long senderId, ApprovalDocumentType type,
                                          Long docId, Long requesterId, String requesterName) {
        if (step == null || step.getDelegateUserId() == null
                || !java.util.Objects.equals(step.getDelegateUserId(), senderId)
                || java.util.Objects.equals(step.getApproverUserId(), senderId)) {
            return;
        }
        String link = docPath(type, docId);
        String docTitle = composeDocumentTitle(type, requesterId, requesterName);
        communicationService.notifyUser(step.getApproverUserId(), senderId, "APPROVAL_REQUEST",
                docTitle, "대리 결재가 완료되어 후결 처리가 필요합니다.", link);
    }

    private String composeDocumentTitle(ApprovalDocumentType type, Long userId, String fallbackName) {
        String docTitle = type.getDocumentTitle();
        String name = fallbackName;
        String org = "";
        if (userId != null) {
            UserAccount u = userMapper.findById(userId);
            if (u != null) {
                if (!StringUtils.hasText(name)) name = u.getName();
                if (StringUtils.hasText(u.getTeamName())) org = u.getTeamName();
                else if (StringUtils.hasText(u.getDept())) org = u.getDept();
            }
        }
        StringBuilder sb = new StringBuilder(docTitle);
        if (StringUtils.hasText(org)) sb.append("_").append(org);
        if (StringUtils.hasText(name)) sb.append("_").append(name);
        return sb.toString();
    }

    @Transactional
    public void approveLeave(CustomUserDetails currentUser, Long requestId) {
        LeaveRequest request = getLeaveRequest(requestId);
        if (request.getStatus() == LeaveStatus.CANCEL_PENDING) {
            approveLeaveCancel(currentUser, requestId);
            return;
        }
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 연차만 승인할 수 있습니다.");
        }
        // [A4] 마감 월로의 소급 결재 차단
        requireLeaveMonthsOpen(request, "휴가 결재를 반영");
        ApprovalStep actedStep = approvalService.approvePendingStep(ApprovalDocumentType.LEAVE, requestId, currentUser);
        notifyPostApprovalNeeded(actedStep, currentUser.getUserId(), ApprovalDocumentType.LEAVE,
                requestId, request.getUserId(), request.getMemberName());
        ApprovalStep nextStep = approvalService.pendingStep(ApprovalDocumentType.LEAVE, requestId);
        if (nextStep != null) {
            hrMapper.updateLeaveCurrentStep(requestId, nextStep.getStepOrder());
            notifyTurn(nextStep, currentUser.getUserId(), ApprovalDocumentType.LEAVE,
                    requestId, request.getMemberName(), request.getUserId());
            return;
        }
        // 잔여는 신청 시점에 이미 차감됨. 여기선 상태만 APPROVED로 갱신.
        hrMapper.updateLeaveStatus(requestId, LeaveStatus.APPROVED, currentUser.getUserId(), null);
        approvalService.activateLinkedDelegation(ApprovalDocumentType.LEAVE, requestId);
        storeFinalDocumentSnapshot(ApprovalDocumentType.LEAVE, requestId, currentUser.getUserId());
        notifyDecided(request.getUserId(), currentUser.getUserId(), ApprovalDocumentType.LEAVE,
                requestId, true, null);
    }

    @Transactional
    public void rejectLeave(CustomUserDetails currentUser, Long requestId, String reason) {
        LeaveRequest request = getLeaveRequest(requestId);
        if (request.getStatus() == LeaveStatus.CANCEL_PENDING) {
            rejectLeaveCancel(currentUser, requestId, reason);
            return;
        }
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 연차만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.LEAVE, requestId, currentUser, reason);
        // 반려 → 신청 시 차감했던 잔여를 복구.
        restoreAnnualDeduction(request);
        hrMapper.updateLeaveStatus(requestId, LeaveStatus.REJECTED, currentUser.getUserId(), trimToNull(reason));
        approvalService.dropLinkedDelegation(ApprovalDocumentType.LEAVE, requestId);
        storeFinalDocumentSnapshot(ApprovalDocumentType.LEAVE, requestId, currentUser.getUserId());
        notifyDecided(request.getUserId(), currentUser.getUserId(), ApprovalDocumentType.LEAVE,
                requestId, false, reason);
    }

    @Transactional
    public void cancelLeave(CustomUserDetails currentUser, Long requestId) {
        LeaveRequest request = getLeaveRequest(requestId);
        if (!request.getUserId().equals(currentUser.getUserId()) && !canManageHr(currentUser)) {
            throw new BusinessException("본인 연차 신청만 취소할 수 있습니다.");
        }
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 연차만 취소할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.LEAVE, requestId);
        // 회수 → 신청 시 차감했던 잔여를 복구.
        restoreAnnualDeduction(request);
        hrMapper.updateLeaveStatus(requestId, LeaveStatus.CANCELED, currentUser.getUserId(), null);
        approvalService.dropLinkedDelegation(ApprovalDocumentType.LEAVE, requestId);
    }

    /* ── 같은 날 중복 휴가 차단 ───────────────────────── */

    /**
     * 같은 기간에 이미 잡혀 있는 휴가를 찾는다(없으면 null).
     *
     * <p>예전에는 겹침을 <b>미리보기에서 경고만</b> 하고 실제 신청({@link #requestLeave})은
     * 아무 검사 없이 통과시켰다. 화면 경고를 무시하거나 API 를 직접 부르면 같은 날 휴가를
     * 몇 번이든 낼 수 있었고, 그때마다 잔여가 <b>실제로 차감</b>됐다 —
     * 오전반차를 두 번 내면 1.0일이 깎이지만 실제로는 반나절만 쉰다. 손해가 직원 쪽으로 난다.
     *
     * <p>{@code CANCEL_PENDING}(취소 결재 진행 중)도 겹침으로 본다. 취소가 <b>승인돼야</b>
     * 잔여가 돌아오므로, 그 전까지 그 휴가는 여전히 날짜와 일수를 붙들고 있다.
     */
    private LeaveRequest conflictingLeave(Long userId, String type,
                                          LocalDate start, LocalDate end, Long excludeId) {
        for (int year = start.getYear(); year <= end.getYear(); year++) {
            List<LeaveRequest> mine = hrMapper.findLeaveRequests(userId, null, year);
            if (mine == null) {
                continue;
            }
            for (LeaveRequest existing : mine) {
                if (excludeId != null && excludeId.equals(existing.getId())) {
                    continue;
                }
                if (!holdsLeaveDays(existing.getStatus())) {
                    continue;
                }
                if (!datesOverlap(existing.getStartDate(), existing.getEndDate(), start, end)) {
                    continue;
                }
                if (halvesShareTheDay(type, start, end, existing)) {
                    continue;
                }
                return existing;
            }
        }
        return null;
    }

    /** 아직 연차를 붙들고 있는 상태 — 취소는 <b>승인돼야</b> 잔여가 돌아온다. */
    private static boolean holdsLeaveDays(LeaveStatus status) {
        return status == LeaveStatus.PENDING
                || status == LeaveStatus.APPROVED
                || status == LeaveStatus.CANCEL_PENDING;
    }

    /**
     * 오전반차와 오후반차가 하루를 나눠 갖는 정상 조합인지 — 이건 겹침이 아니다.
     *
     * <p>오전은 연차, 오후는 보건휴가처럼 종류를 달리해 하루를 채우는 신청이 실제로 있다.
     * 그래서 무조건 막으면 안 된다. 다만 <b>오전+오전</b>이나 <b>종일+반차</b>는 겹침이다.
     * 반일 판정은 고정 코드(AM_HALF/PM_HALF)로만 하므로, 그 밖의 반일 종류를 두 건 내면
     * 막힌다 — 덜 허용하는 쪽이 안전하다.
     */
    private static boolean halvesShareTheDay(String type, LocalDate start, LocalDate end, LeaveRequest existing) {
        boolean sameSingleDay = start.equals(end)
                && start.equals(existing.getStartDate())
                && start.equals(existing.getEndDate());
        if (!sameSingleDay) {
            return false;
        }
        return (isAmHalf(type) && isPmHalf(existing.getType()))
                || (isPmHalf(type) && isAmHalf(existing.getType()));
    }

    private String overlapMessage(LeaveRequest conflict) {
        String label = StringUtils.hasText(conflict.getTypeLabel()) ? conflict.getTypeLabel() : "휴가";
        String period = conflict.getStartDate().equals(conflict.getEndDate())
                ? String.valueOf(conflict.getStartDate())
                : conflict.getStartDate() + " ~ " + conflict.getEndDate();
        String status = conflict.getStatus() != null ? conflict.getStatus().getLabel() : "-";
        return "같은 기간에 이미 신청된 휴가가 있습니다 — " + label + " " + period + "(" + status + ")."
                + " 오전·오후 반차로 하루를 나눠 쓰는 경우가 아니면 겹쳐 신청할 수 없습니다.";
    }

    /** [C2] 연차 잔여 차감 대상인지 — leave_types.deduct_annual(V76) 기준. */
    private boolean isAnnualCountedType(String type) {
        return Boolean.TRUE.equals(leaveTypeDef(type).getDeductAnnual());
    }

    private void restoreAnnualDeduction(LeaveRequest request) {
        BigDecimal deducted = request.getAnnualDeductedDays();
        if (deducted != null && deducted.signum() > 0) {
            hrMapper.increaseUsedLeave(request.getUserId(), request.getStartDate().getYear(), deducted.negate());
        }
    }

    /* ── [C2] 휴가 종류 정의 (V76) ── */

    @Transactional(readOnly = true)
    public List<com.portfolio.worksystem.model.LeaveTypeDef> leaveTypeDefs() {
        return hrMapper.findLeaveTypeDefs();
    }

    @Transactional(readOnly = true)
    public List<com.portfolio.worksystem.model.LeaveTypeDef> activeLeaveTypeDefs() {
        return hrMapper.findLeaveTypeDefs().stream()
                .filter(def -> Boolean.TRUE.equals(def.getActive()))
                .toList();
    }

    /** [UX11] 코드 → 정의 조회(leave_types가 단일 진실). 미등록 코드는 신청 불가. */
    private com.portfolio.worksystem.model.LeaveTypeDef leaveTypeDef(String code) {
        com.portfolio.worksystem.model.LeaveTypeDef def =
                code == null || code.isBlank() ? null : hrMapper.findLeaveTypeDef(code.trim());
        if (def == null) {
            throw new BusinessException("등록되지 않은 휴가 종류입니다: " + code);
        }
        return def;
    }

    /** 반차(오전/오후) 판정 — 지각/조퇴 기준 시각 보정에 쓰는 고정 시맨틱 코드. */
    private static boolean isAmHalf(String type) { return "AM_HALF".equals(type); }
    private static boolean isPmHalf(String type) { return "PM_HALF".equals(type); }

    /** [UX11] 공통 필드 검증(신규/수정 공용). */
    private void validateLeaveTypeFields(com.portfolio.worksystem.model.LeaveTypeDef form, String code) {
        if (!StringUtils.hasText(form.getLabel())) {
            throw new BusinessException("휴가 종류 이름을 입력하세요.");
        }
        form.setLabel(form.getLabel().trim());
        if (form.getDayWeight() == null
                || (form.getDayWeight().compareTo(BigDecimal.ONE) != 0
                    && form.getDayWeight().compareTo(new BigDecimal("0.5")) != 0)) {
            throw new BusinessException("차감 가중치는 1.0(전일) 또는 0.5(반일)만 가능합니다.");
        }
        boolean isHalfCode = "AM_HALF".equals(code) || "PM_HALF".equals(code);
        if (isHalfCode && form.getDayWeight().compareTo(new BigDecimal("0.5")) != 0) {
            throw new BusinessException("오전/오후 반차의 가중치는 0.5로 고정입니다(지각 판정 연동).");
        }
        if (form.getDeductAnnual() == null) {
            form.setDeductAnnual(Boolean.FALSE);
        }
        if (form.getEvidenceRequired() == null) {
            form.setEvidenceRequired(Boolean.FALSE);
        }
        if (form.getActive() == null) {
            form.setActive(Boolean.TRUE);
        }
    }

    /** [UX11] 신규 휴가 종류 추가 — 코드는 영문 대문자/숫자/_. */
    @Transactional
    public void createLeaveTypeDef(CustomUserDetails currentUser, com.portfolio.worksystem.model.LeaveTypeDef form) {
        requireHrManager(currentUser);
        String code = form.getCode() == null ? "" : form.getCode().trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{2,30}")) {
            throw new BusinessException("휴가 종류 코드는 영문 대문자/숫자/_ 2~30자로 입력하세요.");
        }
        if (hrMapper.findLeaveTypeDef(code) != null) {
            throw new BusinessException("이미 존재하는 휴가 종류 코드입니다: " + code);
        }
        form.setCode(code);
        validateLeaveTypeFields(form, code);
        if (form.getSortOrder() == null || form.getSortOrder() == 0) {
            form.setSortOrder(hrMapper.findLeaveTypeDefs().size() + 1);
        }
        hrMapper.insertLeaveTypeDef(form);
    }

    @Transactional
    public void updateLeaveTypeDef(CustomUserDetails currentUser, com.portfolio.worksystem.model.LeaveTypeDef form) {
        requireHrManager(currentUser);
        if (form.getCode() == null || hrMapper.findLeaveTypeDef(form.getCode()) == null) {
            throw new BusinessException("수정할 휴가 종류를 찾을 수 없습니다.");
        }
        validateLeaveTypeFields(form, form.getCode());
        if (form.getSortOrder() == null) {
            form.setSortOrder(0);
        }
        hrMapper.updateLeaveTypeDef(form);
    }

    /** 취소 결재 상신 전 미리보기 — 검증 통과한 휴가와 취소 결재선(기안자=휴가 소유자)을 돌려준다. */
    public record LeaveCancelPreview(LeaveRequest request, List<ApprovalStep> steps) {
    }

    @Transactional(readOnly = true)
    public LeaveCancelPreview leaveCancelPreview(CustomUserDetails currentUser, Long requestId) {
        LeaveRequest request = getLeaveRequest(requestId);
        validateLeaveCancelable(currentUser, request);
        List<ApprovalStep> steps = approvalService.previewRouteFor(request.getUserId(),
                ApprovalDocumentType.LEAVE, request.getStartDate(), request.getEndDate());
        return new LeaveCancelPreview(request, steps);
    }

    private void validateLeaveCancelable(CustomUserDetails currentUser, LeaveRequest request) {
        if (request.getStatus() != LeaveStatus.APPROVED) {
            throw new BusinessException("승인된 휴가만 취소 신청할 수 있습니다.");
        }
        if (!request.getUserId().equals(currentUser.getUserId()) && !canManageHr(currentUser)) {
            throw new BusinessException("본인 휴가만 취소 신청할 수 있습니다.");
        }
    }

    /** 승인 완료된 휴가 취소 신청 — 같은 결재 라인으로 새 결재 흐름 시작. */
    @Transactional
    public void requestLeaveCancel(CustomUserDetails currentUser, Long requestId, String cancelReason) {
        LeaveRequest request = getLeaveRequest(requestId);
        validateLeaveCancelable(currentUser, request);
        requireLeaveMonthsOpen(request, "휴가 취소를 신청");
        List<ApprovalStep> steps = approvalService.createSteps(ApprovalDocumentType.LEAVE, requestId,
                request.getUserId(), request.getStartDate(), request.getEndDate());
        if (steps.isEmpty()) {
            throw new BusinessException("연차 결재경로가 설정되어 있지 않습니다.");
        }
        hrMapper.updateLeaveStatus(requestId, LeaveStatus.CANCEL_PENDING, currentUser.getUserId(), null);
        hrMapper.updateLeaveCancelReason(requestId, trimToNull(cancelReason));
        hrMapper.updateLeaveCurrentStep(requestId, steps.get(0).getStepOrder());
        afterSubmit(steps, currentUser, ApprovalDocumentType.LEAVE, requestId,
                () -> approveLeaveCancel(currentUser, requestId));
    }

    @Transactional
    public void approveLeaveCancel(CustomUserDetails currentUser, Long requestId) {
        LeaveRequest request = getLeaveRequest(requestId);
        if (request.getStatus() != LeaveStatus.CANCEL_PENDING) {
            throw new BusinessException("취소 결재 진행 중인 휴가만 승인할 수 있습니다.");
        }
        requireLeaveMonthsOpen(request, "휴가 취소 결재를 반영");
        ApprovalStep actedStep = approvalService.approvePendingStep(ApprovalDocumentType.LEAVE, requestId, currentUser);
        notifyPostApprovalNeeded(actedStep, currentUser.getUserId(), ApprovalDocumentType.LEAVE_CANCEL,
                requestId, request.getUserId(), request.getMemberName());
        ApprovalStep nextStep = approvalService.pendingStep(ApprovalDocumentType.LEAVE, requestId);
        if (nextStep != null) {
            hrMapper.updateLeaveCurrentStep(requestId, nextStep.getStepOrder());
            notifyTurn(nextStep, currentUser.getUserId(), ApprovalDocumentType.LEAVE_CANCEL,
                    requestId, request.getMemberName(), request.getUserId());
            return;
        }
        // 취소 결재 모두 통과 → 잔여 복구 + 상태 CANCEL_APPROVED.
        restoreAnnualDeduction(request);
        hrMapper.updateLeaveStatus(requestId, LeaveStatus.CANCEL_APPROVED, currentUser.getUserId(), null);
        // 승인된 휴가를 취소했으면 그 휴가에 묶인 대리 지정도 없앤다.
        approvalService.dropLinkedDelegation(ApprovalDocumentType.LEAVE, requestId);
        storeFinalDocumentSnapshot(ApprovalDocumentType.LEAVE, requestId, currentUser.getUserId());
        notifyDecided(request.getUserId(), currentUser.getUserId(), ApprovalDocumentType.LEAVE_CANCEL,
                requestId, true, null);
    }

    @Transactional
    public void rejectLeaveCancel(CustomUserDetails currentUser, Long requestId, String reason) {
        LeaveRequest request = getLeaveRequest(requestId);
        if (request.getStatus() != LeaveStatus.CANCEL_PENDING) {
            throw new BusinessException("취소 결재 진행 중인 휴가만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.LEAVE, requestId, currentUser, reason);
        // 반려 → 다시 APPROVED. 잔여 그대로 유지.
        hrMapper.updateLeaveStatus(requestId, LeaveStatus.APPROVED, currentUser.getUserId(), trimToNull(reason));
        notifyDecided(request.getUserId(), currentUser.getUserId(), ApprovalDocumentType.LEAVE_CANCEL,
                requestId, false, reason);
    }

    /** 본인이 올린 취소 결재 회수 — 결재 라인 삭제 + 다시 APPROVED. */
    @Transactional
    public void withdrawLeaveCancel(CustomUserDetails currentUser, Long requestId) {
        LeaveRequest request = getLeaveRequest(requestId);
        if (request.getStatus() != LeaveStatus.CANCEL_PENDING) {
            throw new BusinessException("취소 결재 진행 중인 휴가만 회수할 수 있습니다.");
        }
        if (!request.getUserId().equals(currentUser.getUserId()) && !canManageHr(currentUser)) {
            throw new BusinessException("본인 휴가만 회수할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.LEAVE, requestId);
        hrMapper.updateLeaveStatus(requestId, LeaveStatus.APPROVED, currentUser.getUserId(), null);
    }

    /** 본인이 해당 연도에 사용한(PENDING+APPROVED) 휴가의 종류별 합계. */
    @Transactional(readOnly = true)
    public java.util.LinkedHashMap<String, BigDecimal> leaveUsageByType(Long userId, int year) {
        java.util.LinkedHashMap<String, BigDecimal> result = new java.util.LinkedHashMap<>();
        for (com.portfolio.worksystem.model.LeaveTypeDef t : hrMapper.findLeaveTypeDefs()) {
            result.put(t.getLabel(), BigDecimal.ZERO);
        }
        if (userId == null) return result;
        for (LeaveRequest r : hrMapper.findLeaveRequests(userId, null, year)) {
            if (r.getType() == null || r.getDays() == null) continue;
            if (!holdsLeaveDays(r.getStatus())) continue;
            result.merge(r.getTypeLabel(), r.getDays(), BigDecimal::add);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<FlexTimeRequest> flexTimeRequests(CustomUserDetails currentUser, Integer year) {
        var visibility = visibility(currentUser);
        return hrMapper.findFlexTimeRequests(visibility.userId(), visibility.teamId(), year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> flexTimeApprovalSteps(Long requestId) {
        return approvalService.steps(ApprovalDocumentType.FLEX_TIME, requestId);
    }

    @Transactional
    public void requestFlexTime(CustomUserDetails currentUser, FlexTimeRequestForm form) {
        if (form.shiftGroup() == null || form.shiftGroup().isBlank()) {
            throw new BusinessException("시차출근 조를 선택하세요.");
        }
        ShiftGroupDef shiftGroup = hrMapper.findShiftGroupByCode(form.shiftGroup());
        if (shiftGroup == null || !Boolean.TRUE.equals(shiftGroup.getActive())) {
            throw new BusinessException("사용할 수 없는 시차출근 조입니다.");
        }
        if (form.startDate() == null || form.endDate() == null) {
            throw new BusinessException("시차출근 시작일과 종료일을 입력하세요.");
        }
        if (form.startDate().isAfter(form.endDate())) {
            throw new BusinessException("시차출근 종료일은 시작일보다 빠를 수 없습니다.");
        }
        if (hrMapper.existsOverlappingFlexTime(currentUser.getUserId(), form.startDate(), form.endDate())) {
            throw new BusinessException("이미 신청 중이거나 승인된 시차출근 기간과 겹칩니다.");
        }
        requireApprovalRoute(currentUser, ApprovalDocumentType.FLEX_TIME, form.startDate(), form.endDate());
        FlexTimeRequest request = new FlexTimeRequest();
        request.setUserId(currentUser.getUserId());
        request.setShiftGroup(form.shiftGroup());
        request.setStartDate(form.startDate());
        request.setEndDate(form.endDate());
        request.setReason(trimToNull(form.reason()));
        request.setStatus(FlexTimeStatus.PENDING);
        request.setCurrentApprovalStep(1);
        hrMapper.insertFlexTimeRequest(request);
        List<ApprovalStep> steps = approvalService.createSteps(ApprovalDocumentType.FLEX_TIME, request.getId(),
                currentUser.getUserId(), form.startDate(), form.endDate());
        if (steps.isEmpty()) {
            throw new BusinessException("시차출근 결재경로가 설정되어 있지 않습니다.");
        }
        hrMapper.updateFlexTimeCurrentStep(request.getId(), steps.get(0).getStepOrder());
        afterSubmit(steps, currentUser, ApprovalDocumentType.FLEX_TIME, request.getId(),
                () -> approveFlexTime(currentUser, request.getId()));
    }

    @Transactional
    public void approveFlexTime(CustomUserDetails currentUser, Long requestId) {
        FlexTimeRequest request = getFlexTimeRequest(requestId);
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 시차출근 신청만 승인할 수 있습니다.");
        }
        ApprovalStep actedStep = approvalService.approvePendingStep(ApprovalDocumentType.FLEX_TIME, requestId, currentUser);
        notifyPostApprovalNeeded(actedStep, currentUser.getUserId(), ApprovalDocumentType.FLEX_TIME,
                requestId, request.getUserId(), request.getMemberName());
        ApprovalStep nextStep = approvalService.pendingStep(ApprovalDocumentType.FLEX_TIME, requestId);
        if (nextStep != null) {
            hrMapper.updateFlexTimeCurrentStep(requestId, nextStep.getStepOrder());
            notifyTurn(nextStep, currentUser.getUserId(), ApprovalDocumentType.FLEX_TIME,
                    requestId, request.getMemberName(), request.getUserId());
            return;
        }
        hrMapper.updateFlexTimeStatus(requestId, FlexTimeStatus.APPROVED, currentUser.getUserId(), null);
        storeFinalDocumentSnapshot(ApprovalDocumentType.FLEX_TIME, requestId, currentUser.getUserId());
        notifyDecided(request.getUserId(), currentUser.getUserId(), ApprovalDocumentType.FLEX_TIME,
                requestId, true, null);
    }

    @Transactional
    public void rejectFlexTime(CustomUserDetails currentUser, Long requestId, String reason) {
        FlexTimeRequest request = getFlexTimeRequest(requestId);
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 시차출근 신청만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.FLEX_TIME, requestId, currentUser, reason);
        hrMapper.updateFlexTimeStatus(requestId, FlexTimeStatus.REJECTED, currentUser.getUserId(), trimToNull(reason));
        storeFinalDocumentSnapshot(ApprovalDocumentType.FLEX_TIME, requestId, currentUser.getUserId());
        notifyDecided(request.getUserId(), currentUser.getUserId(), ApprovalDocumentType.FLEX_TIME,
                requestId, false, reason);
    }

    @Transactional
    public void cancelFlexTime(CustomUserDetails currentUser, Long requestId) {
        FlexTimeRequest request = getFlexTimeRequest(requestId);
        if (!request.getUserId().equals(currentUser.getUserId()) && !canManageHr(currentUser)) {
            throw new BusinessException("본인 시차출근 신청만 취소할 수 있습니다.");
        }
        if (!request.isPending()) {
            throw new BusinessException("승인 대기 상태의 시차출근 신청만 취소할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.FLEX_TIME, requestId);
        hrMapper.updateFlexTimeStatus(requestId, FlexTimeStatus.CANCELED, currentUser.getUserId(), null);
    }

    private FlexTimeRequest getFlexTimeRequest(Long requestId) {
        FlexTimeRequest request = hrMapper.findFlexTimeRequest(requestId);
        if (request == null) {
            throw new BusinessException("시차출근 신청을 찾을 수 없습니다.");
        }
        return request;
    }

    @Transactional(readOnly = true)
    public List<AttendanceCorrection> attendanceCorrections(CustomUserDetails currentUser, Integer year) {
        var visibility = visibility(currentUser);
        return hrMapper.findAttendanceCorrections(visibility.userId(), visibility.teamId(), year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> attendanceCorrectionApprovalSteps(Long correctionId) {
        return approvalService.steps(ApprovalDocumentType.ATTENDANCE_CORRECTION, correctionId);
    }

    @Transactional
    public void requestAttendanceCorrection(CustomUserDetails currentUser, AttendanceCorrectionForm form) {
        if (form.workDate() == null) {
            throw new BusinessException("정정할 근무 일자를 선택하세요.");
        }
        if (form.workDate().isAfter(LocalDate.now(SERVICE_ZONE))) {
            throw new BusinessException("미래 일자는 근태 정정 신청을 할 수 없습니다.");
        }
        requireMonthOpen(form.workDate(), "근태 정정을 신청"); // [A4]
        if (form.requestedCheckIn() == null && form.requestedCheckOut() == null) {
            throw new BusinessException("정정할 출근 또는 퇴근 시각을 입력하세요.");
        }
        if (form.requestedCheckIn() != null && form.requestedCheckOut() != null
                && !form.requestedCheckIn().isBefore(form.requestedCheckOut())) {
            throw new BusinessException("출근 시각은 퇴근 시각보다 빨라야 합니다.");
        }
        requireApprovalRoute(currentUser, ApprovalDocumentType.ATTENDANCE_CORRECTION, form.workDate(), form.workDate());
        if (hrMapper.existsPendingCorrection(currentUser.getUserId(), form.workDate())) {
            throw new BusinessException("이미 해당 일자에 처리 대기 중인 근태 정정 신청이 있습니다.");
        }
        AttendanceRecord record = hrMapper.findAttendanceByUserDate(currentUser.getUserId(), form.workDate());
        LocalTime effectiveCheckIn = form.requestedCheckIn() != null
                ? form.requestedCheckIn()
                : record != null && record.getCheckInAt() != null ? record.getCheckInAt().toLocalTime() : null;
        LocalTime effectiveCheckOut = form.requestedCheckOut() != null
                ? form.requestedCheckOut()
                : record != null && record.getCheckOutAt() != null ? record.getCheckOutAt().toLocalTime() : null;
        if (effectiveCheckIn != null && effectiveCheckOut != null && !effectiveCheckIn.isBefore(effectiveCheckOut)) {
            throw new BusinessException("정정 후 출근 시각은 퇴근 시각보다 빨라야 합니다.");
        }
        AttendanceCorrection correction = new AttendanceCorrection();
        correction.setUserId(currentUser.getUserId());
        correction.setWorkDate(form.workDate());
        if (record != null) {
            correction.setAttendanceRecordId(record.getId());
            correction.setOriginalCheckInAt(record.getCheckInAt());
            correction.setOriginalCheckOutAt(record.getCheckOutAt());
            correction.setOriginalStatus(record.getStatus());
        }
        correction.setRequestedCheckIn(form.requestedCheckIn());
        correction.setRequestedCheckOut(form.requestedCheckOut());
        correction.setReason(trimToNull(form.reason()));
        correction.setStatus(AttendanceCorrectionStatus.PENDING);
        correction.setCurrentApprovalStep(1);
        hrMapper.insertAttendanceCorrection(correction);
        List<ApprovalStep> steps = approvalService.createSteps(ApprovalDocumentType.ATTENDANCE_CORRECTION,
                correction.getId(), currentUser.getUserId(), form.workDate(), form.workDate());
        if (steps.isEmpty()) {
            throw new BusinessException("근태정정 결재경로가 설정되어 있지 않습니다.");
        }
        hrMapper.updateAttendanceCorrectionCurrentStep(correction.getId(), steps.get(0).getStepOrder());
        afterSubmit(steps, currentUser, ApprovalDocumentType.ATTENDANCE_CORRECTION, correction.getId(),
                () -> approveAttendanceCorrection(currentUser, correction.getId()));
    }

    @Transactional
    public void approveAttendanceCorrection(CustomUserDetails currentUser, Long correctionId) {
        AttendanceCorrection correction = getAttendanceCorrection(correctionId);
        if (!correction.isPending()) {
            throw new BusinessException("승인 대기 상태의 근태 정정만 승인할 수 있습니다.");
        }
        requireMonthOpen(correction.getWorkDate(), "근태 정정을 승인"); // [A4]
        ApprovalStep actedStep = approvalService.approvePendingStep(ApprovalDocumentType.ATTENDANCE_CORRECTION, correctionId, currentUser);
        notifyPostApprovalNeeded(actedStep, currentUser.getUserId(), ApprovalDocumentType.ATTENDANCE_CORRECTION,
                correctionId, correction.getUserId(), correction.getMemberName());
        ApprovalStep nextStep = approvalService.pendingStep(ApprovalDocumentType.ATTENDANCE_CORRECTION, correctionId);
        if (nextStep != null) {
            hrMapper.updateAttendanceCorrectionCurrentStep(correctionId, nextStep.getStepOrder());
            notifyTurn(nextStep, currentUser.getUserId(), ApprovalDocumentType.ATTENDANCE_CORRECTION,
                    correctionId, correction.getMemberName(), correction.getUserId());
            return;
        }
        applyAttendanceCorrection(correction);
        hrMapper.updateAttendanceCorrectionStatus(correctionId, AttendanceCorrectionStatus.APPROVED, currentUser.getUserId(), null);
        storeFinalDocumentSnapshot(ApprovalDocumentType.ATTENDANCE_CORRECTION, correctionId, currentUser.getUserId());
        panelRefreshPublisher.publishAllActive("attendance-correction");
        notifyDecided(correction.getUserId(), currentUser.getUserId(), ApprovalDocumentType.ATTENDANCE_CORRECTION,
                correctionId, true, null);
    }

    @Transactional
    public void rejectAttendanceCorrection(CustomUserDetails currentUser, Long correctionId, String reason) {
        AttendanceCorrection correction = getAttendanceCorrection(correctionId);
        if (!correction.isPending()) {
            throw new BusinessException("승인 대기 상태의 근태 정정만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.ATTENDANCE_CORRECTION, correctionId, currentUser, reason);
        hrMapper.updateAttendanceCorrectionStatus(correctionId, AttendanceCorrectionStatus.REJECTED, currentUser.getUserId(), trimToNull(reason));
        storeFinalDocumentSnapshot(ApprovalDocumentType.ATTENDANCE_CORRECTION, correctionId, currentUser.getUserId());
        notifyDecided(correction.getUserId(), currentUser.getUserId(), ApprovalDocumentType.ATTENDANCE_CORRECTION,
                correctionId, false, reason);
    }

    @Transactional
    public void cancelAttendanceCorrection(CustomUserDetails currentUser, Long correctionId) {
        AttendanceCorrection correction = getAttendanceCorrection(correctionId);
        if (!correction.getUserId().equals(currentUser.getUserId()) && !canManageHr(currentUser)) {
            throw new BusinessException("본인 근태 정정 신청만 취소할 수 있습니다.");
        }
        if (!correction.isPending()) {
            throw new BusinessException("승인 대기 상태의 근태 정정만 취소할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.ATTENDANCE_CORRECTION, correctionId);
        hrMapper.updateAttendanceCorrectionStatus(correctionId, AttendanceCorrectionStatus.CANCELED, currentUser.getUserId(), null);
    }

    private void applyAttendanceCorrection(AttendanceCorrection correction) {
        LocalDateTime checkInAt = correction.getRequestedCheckIn() != null
                ? LocalDateTime.of(correction.getWorkDate(), correction.getRequestedCheckIn())
                : correction.getOriginalCheckInAt();
        LocalDateTime checkOutAt = correction.getRequestedCheckOut() != null
                ? LocalDateTime.of(correction.getWorkDate(), correction.getRequestedCheckOut())
                : correction.getOriginalCheckOutAt();
        if (checkInAt != null && checkOutAt != null && !checkInAt.isBefore(checkOutAt)) {
            throw new BusinessException("정정 후 출근 시각은 퇴근 시각보다 빨라야 합니다.");
        }
        AttendanceStatus status = computeAttendanceStatus(correction.getUserId(), correction.getWorkDate(), checkInAt, checkOutAt);
        if (correction.getAttendanceRecordId() != null) {
            hrMapper.updateAttendanceRecord(correction.getAttendanceRecordId(), checkInAt, checkOutAt, status);
            return;
        }
        AttendanceRecord record = new AttendanceRecord();
        record.setUserId(correction.getUserId());
        record.setWorkDate(correction.getWorkDate());
        record.setCheckInAt(checkInAt);
        record.setCheckOutAt(checkOutAt);
        record.setStatus(status);
        record.setNote("근태 정정 승인");
        record.setSource("CORRECTION");
        hrMapper.insertAttendance(record);
    }

    private AttendanceStatus computeAttendanceStatus(Long userId, LocalDate workDate,
                                                     LocalDateTime checkInAt, LocalDateTime checkOutAt) {
        if (checkInAt == null) {
            return AttendanceStatus.ABSENT;
        }
        WorkSchedule schedule = workSchedule(userId, workDate);
        LeaveRequest leave = hrMapper.findApprovedLeaveForDate(userId, workDate);
        AttendanceStatus status = checkInAt.toLocalTime().isAfter(judgmentStartTime(schedule, leave))
                ? AttendanceStatus.LATE
                : AttendanceStatus.NORMAL;
        if (checkOutAt != null && checkOutAt.toLocalTime().isBefore(judgmentEndTime(schedule, leave))) {
            status = AttendanceStatus.EARLY_LEAVE;
        }
        return status;
    }

    /** 반차 승인일의 지각 판정 기준 — 오전 반차면 기준 출근시각을 반일(4시간)만큼 늦춘다. */
    private static LocalTime judgmentStartTime(WorkSchedule schedule, LeaveRequest leave) {
        if (leave != null && isAmHalf(leave.getType())) {
            return schedule.startTime().plusHours(HALF_DAY_HOURS);
        }
        return schedule.startTime();
    }

    /** 반차 승인일의 조퇴 판정 기준 — 오후 반차면 기준 퇴근시각을 반일(4시간)만큼 당긴다. */
    private static LocalTime judgmentEndTime(WorkSchedule schedule, LeaveRequest leave) {
        if (leave != null && isPmHalf(leave.getType())) {
            return schedule.endTime().minusHours(HALF_DAY_HOURS);
        }
        return schedule.endTime();
    }

    /** 반차가 아닌 승인 휴가(종일 부재)인지 — 출근 체크 차단·일별 현황 '휴무(휴가)' 표시 대상. */
    private static boolean isFullDayLeave(LeaveRequest leave) {
        return leave != null && !isAmHalf(leave.getType()) && !isPmHalf(leave.getType());
    }

    private static List<LeaveRequest> approvedLeavesForDate(List<LeaveRequest> leaves, Long userId, LocalDate date) {
        return leaves.stream()
                .filter(leave -> leave.getUserId().equals(userId))
                .filter(leave -> !date.isBefore(leave.getStartDate()) && !date.isAfter(leave.getEndDate()))
                .toList();
    }

    /**
     * 그날 하루가 승인된 휴가로 <b>전부</b> 덮이는지.
     *
     * <p>종일 휴가 한 건이거나, <b>오전반차와 오후반차가 함께</b> 있으면 종일 부재다.
     *
     * <p>예전에는 그날의 휴가를 하나만 골라({@code findFirst}) 종일 여부를 봤다. 그래서
     * 오전·오후 반차를 따로 신청해 둘 다 승인되면 어느 쪽도 종일이 아니라서, 출근 기록이
     * 없는 과거 날짜가 <b>결근으로 잡혔다</b>. 하루를 정당하게 다 쉬었는데 결근이 된다.
     *
     * <p>휴가 겹침 검사는 예외가 아니라 경고라서(신청 화면에서 안내만 한다) 이 조합은
     * 실제로 만들어질 수 있다 — 오전은 연차, 오후는 보건휴가처럼 종류가 다를 때가 그렇다.
     */
    private static boolean isFullDayCovered(List<LeaveRequest> leavesOfDate) {
        if (leavesOfDate.isEmpty()) {
            return false;
        }
        if (leavesOfDate.stream().anyMatch(HrService::isFullDayLeave)) {
            return true;
        }
        boolean am = leavesOfDate.stream().anyMatch(l -> isAmHalf(l.getType()));
        boolean pm = leavesOfDate.stream().anyMatch(l -> isPmHalf(l.getType()));
        return am && pm;
    }

    /** 종일로 인정된 날 화면에 쓸 이름 — 반차 두 건이면 둘을 병기한다. */
    private static String fullDayLeaveLabel(List<LeaveRequest> leavesOfDate) {
        return leavesOfDate.stream()
                .map(LeaveRequest::getTypeLabel)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    private AttendanceCorrection getAttendanceCorrection(Long correctionId) {
        AttendanceCorrection correction = hrMapper.findAttendanceCorrection(correctionId);
        if (correction == null) {
            throw new BusinessException("근태 정정 신청을 찾을 수 없습니다.");
        }
        return correction;
    }

    @Transactional(readOnly = true)
    public AttendanceSetting attendanceSetting() {
        AttendanceSetting setting = hrMapper.findAttendanceSetting();
        if (setting != null) {
            return setting;
        }
        setting = new AttendanceSetting();
        setting.setId(1);
        setting.setDefaultStartTime(FALLBACK_START);
        setting.setDefaultEndTime(FALLBACK_END);
        return setting;
    }

    /** 회사 휴무일이거나 휴무 요일(특정일 예외로 근무일이 된 날 제외)인지. */
    @Transactional(readOnly = true)
    public boolean isOffDay(LocalDate date) {
        return hrMapper.findCompanyHoliday(date) != null
                || (weekDayOf(weekSchedule(), date).isOff() && hrMapper.findAttendanceOverride(date) == null);
    }

    @Transactional(readOnly = true)
    public WorkSchedule workSchedule(Long userId, LocalDate workDate) {
        AttendanceSetting setting = attendanceSetting();
        AttendanceDayOverride override = hrMapper.findAttendanceOverride(workDate);
        return WorkScheduleResolver.resolve(setting.getDefaultStartTime(), setting.getDefaultEndTime(),
                override, approvedShiftGroup(userId, workDate), weekDayOf(weekSchedule(), workDate));
    }

    private ShiftGroupDef approvedShiftGroup(Long userId, LocalDate workDate) {
        if (userId == null) {
            return null;
        }
        FlexTimeRequest flex = hrMapper.findApprovedFlexTimeForDate(userId, workDate);
        return flex == null ? null : flex.toShiftGroupDef();
    }

    private ShiftGroupDef approvedShiftForDate(List<FlexTimeRequest> flexes, Long userId, LocalDate date) {
        return flexes.stream()
                .filter(flex -> flex.getUserId().equals(userId))
                .filter(flex -> !date.isBefore(flex.getStartDate()) && !date.isAfter(flex.getEndDate()))
                .map(FlexTimeRequest::toShiftGroupDef)
                .findFirst()
                .orElse(null);
    }

    /* ── 요일별 근무시간표 (V70) — 주 4.5일제 등 주간 패턴 ── */

    @Transactional(readOnly = true)
    public Map<Integer, AttendanceWeekDay> weekSchedule() {
        Map<Integer, AttendanceWeekDay> map = new HashMap<>();
        for (AttendanceWeekDay day : hrMapper.findWeekSchedule()) {
            map.put(day.getDayOfWeek(), day);
        }
        for (int dow = 1; dow <= 7; dow++) {
            map.computeIfAbsent(dow, HrService::defaultWeekDay);
        }
        return map;
    }

    private static AttendanceWeekDay defaultWeekDay(int dayOfWeek) {
        AttendanceWeekDay day = new AttendanceWeekDay();
        day.setDayOfWeek(dayOfWeek);
        day.setWorkType(dayOfWeek >= 6 ? "OFF" : "FULL");
        return day;
    }

    private static AttendanceWeekDay weekDayOf(Map<Integer, AttendanceWeekDay> week, LocalDate date) {
        AttendanceWeekDay day = week.get(date.getDayOfWeek().getValue());
        return day != null ? day : defaultWeekDay(date.getDayOfWeek().getValue());
    }

    @Transactional
    public void saveWeekSchedule(CustomUserDetails currentUser, List<AttendanceWeekDay> days) {
        requireHrManager(currentUser);
        if (days == null || days.size() != 7) {
            throw new BusinessException("요일별 근무시간표는 7개 요일 전체를 저장해야 합니다.");
        }
        boolean anyWorkday = false;
        for (AttendanceWeekDay day : days) {
            if (day.getDayOfWeek() == null || day.getDayOfWeek() < 1 || day.getDayOfWeek() > 7) {
                throw new BusinessException("요일 값이 올바르지 않습니다.");
            }
            String type = day.getWorkType() == null ? "" : day.getWorkType().toUpperCase();
            if (!type.equals("FULL") && !type.equals("HALF") && !type.equals("OFF")) {
                throw new BusinessException("근무 구분은 전일/반일/휴무 중 하나여야 합니다.");
            }
            day.setWorkType(type);
            if (type.equals("OFF")) {
                day.setStartTime(null);
                day.setEndTime(null);
            } else {
                anyWorkday = true;
                boolean hasStart = day.getStartTime() != null;
                boolean hasEnd = day.getEndTime() != null;
                if (hasStart != hasEnd) {
                    throw new BusinessException(day.dayLabel() + "요일: 출근·퇴근 시각은 함께 입력하거나 둘 다 비워두세요.");
                }
                if (hasStart && !day.getStartTime().isBefore(day.getEndTime())) {
                    throw new BusinessException(day.dayLabel() + "요일: 퇴근 시각은 출근 시각보다 늦어야 합니다.");
                }
            }
        }
        if (!anyWorkday) {
            throw new BusinessException("최소 1개 요일은 근무일이어야 합니다.");
        }
        for (AttendanceWeekDay day : days) {
            hrMapper.upsertWeekDay(day);
        }
    }

    /* ── 시차출근 조 관리 (V69) ── */

    @Transactional(readOnly = true)
    public List<ShiftGroupDef> shiftGroups() {
        return hrMapper.findShiftGroups();
    }

    @Transactional(readOnly = true)
    public List<ShiftGroupDef> activeShiftGroups() {
        return hrMapper.findShiftGroups().stream()
                .filter(group -> Boolean.TRUE.equals(group.getActive()))
                .toList();
    }

    @Transactional
    public void createShiftGroup(CustomUserDetails currentUser, ShiftGroupDef form) {
        requireHrManager(currentUser);
        if (form.getCode() == null || form.getCode().isBlank() || form.getLabel() == null || form.getLabel().isBlank()) {
            throw new BusinessException("조 코드와 이름을 입력하세요.");
        }
        String code = form.getCode().trim().toUpperCase();
        if (!code.matches("[A-Z0-9_-]{1,30}")) {
            throw new BusinessException("조 코드는 영문 대문자/숫자 30자 이내로 입력하세요.");
        }
        if (hrMapper.findShiftGroupByCode(code) != null) {
            throw new BusinessException("이미 존재하는 조 코드입니다: " + code);
        }
        validateShiftTimes(form);
        form.setCode(code);
        form.setActive(Boolean.TRUE);
        if (form.getSortOrder() == null) {
            form.setSortOrder(hrMapper.findShiftGroups().size() + 1);
        }
        hrMapper.insertShiftGroup(form);
    }

    @Transactional
    public void updateShiftGroup(CustomUserDetails currentUser, ShiftGroupDef form) {
        requireHrManager(currentUser);
        if (form.getId() == null) {
            throw new BusinessException("수정할 조를 찾을 수 없습니다.");
        }
        if (form.getLabel() == null || form.getLabel().isBlank()) {
            throw new BusinessException("조 이름을 입력하세요.");
        }
        validateShiftTimes(form);
        if (form.getActive() == null) {
            form.setActive(Boolean.TRUE);
        }
        if (form.getSortOrder() == null) {
            form.setSortOrder(0);
        }
        hrMapper.updateShiftGroup(form);
    }

    private void validateShiftTimes(ShiftGroupDef form) {
        // 시각은 둘 다 비우면(기본 근무시간 따름) 허용, 하나만 채우면 오류
        boolean hasStart = form.getStartTime() != null;
        boolean hasEnd = form.getEndTime() != null;
        if (hasStart != hasEnd) {
            throw new BusinessException("출근·퇴근 시각은 함께 입력하거나 둘 다 비워두세요(기본 근무시간 적용).");
        }
        if (hasStart && !form.getStartTime().isBefore(form.getEndTime())) {
            throw new BusinessException("퇴근 시각은 출근 시각보다 늦어야 합니다.");
        }
    }

    @Transactional(readOnly = true)
    public List<AttendanceDayOverride> attendanceOverrides(LocalDate fromDate, LocalDate toDate) {
        return hrMapper.findAttendanceOverrides(fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public List<CompanyHoliday> companyHolidays(LocalDate fromDate, LocalDate toDate) {
        return hrMapper.findCompanyHolidays(fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public List<CompanySchedule> companySchedules(LocalDate fromDate, LocalDate toDate) {
        LocalDate start = fromDate == null ? LocalDate.now(SERVICE_ZONE).withDayOfMonth(1) : fromDate;
        LocalDate end = toDate == null ? start.plusMonths(1).minusDays(1) : toDate;
        return hrMapper.findCompanySchedules(start.atStartOfDay(), end.atTime(23, 59, 59));
    }

    @Transactional
    public void createCompanySchedule(CustomUserDetails currentUser, CompanyScheduleForm form) {
        requireHrManager(currentUser);
        CompanySchedule schedule = new CompanySchedule();
        applyScheduleForm(schedule, form);
        schedule.setCreatedBy(currentUser.getUserId());
        hrMapper.insertCompanySchedule(schedule);
    }

    @Transactional
    public void updateCompanySchedule(CustomUserDetails currentUser, Long id, CompanyScheduleForm form) {
        requireHrManager(currentUser);
        if (id == null) {
            throw new BusinessException("수정할 일정을 찾을 수 없습니다.");
        }
        CompanySchedule schedule = new CompanySchedule();
        schedule.setId(id);
        applyScheduleForm(schedule, form);
        hrMapper.updateCompanySchedule(schedule);
    }

    @Transactional
    public void deleteCompanySchedule(CustomUserDetails currentUser, Long id) {
        requireHrManager(currentUser);
        hrMapper.deleteCompanySchedule(id);
    }

    private void applyScheduleForm(CompanySchedule schedule, CompanyScheduleForm form) {
        if (!StringUtils.hasText(form.title())) {
            throw new BusinessException("일정명을 입력하세요.");
        }
        if (form.startAt() == null || form.endAt() == null) {
            throw new BusinessException("일정 시작과 종료 일시를 입력하세요.");
        }
        if (!form.startAt().isBefore(form.endAt())) {
            throw new BusinessException("일정 종료는 시작보다 늦어야 합니다.");
        }
        schedule.setTitle(form.title().trim());
        schedule.setDescription(trimToNull(form.description()));
        schedule.setStartAt(form.startAt());
        schedule.setEndAt(form.endAt());
        schedule.setColor(normalizeScheduleColor(form.color()));
    }

    @Transactional
    public void updateAttendanceSetting(CustomUserDetails currentUser, AttendanceSettingForm form) {
        requireHrManager(currentUser);
        if (form.defaultStartTime() == null || form.defaultEndTime() == null) {
            throw new BusinessException("기본 출근/퇴근 시간을 입력하세요.");
        }
        if (!form.defaultStartTime().isBefore(form.defaultEndTime())) {
            throw new BusinessException("기본 출근 시간은 퇴근 시간보다 빨라야 합니다.");
        }
        hrMapper.updateAttendanceSetting(form.defaultStartTime(), form.defaultEndTime(), currentUser.getUserId());
    }

    @Transactional
    public void saveAttendanceOverride(CustomUserDetails currentUser, AttendanceOverrideForm form) {
        requireHrManager(currentUser);
        if (form.workDate() == null || form.startTime() == null || form.endTime() == null) {
            throw new BusinessException("예외 일자와 출근/퇴근 시간을 입력하세요.");
        }
        if (!form.startTime().isBefore(form.endTime())) {
            throw new BusinessException("출근 시간은 퇴근 시간보다 빨라야 합니다.");
        }
        AttendanceDayOverride override = new AttendanceDayOverride();
        override.setWorkDate(form.workDate());
        override.setStartTime(form.startTime());
        override.setEndTime(form.endTime());
        override.setNote(trimToNull(form.note()));
        override.setCreatedBy(currentUser.getUserId());
        hrMapper.upsertAttendanceOverride(override);
    }

    @Transactional
    public void deleteAttendanceOverride(CustomUserDetails currentUser, LocalDate workDate) {
        requireHrManager(currentUser);
        hrMapper.deleteAttendanceOverride(workDate);
    }

    @Transactional
    public void saveCompanyHoliday(CustomUserDetails currentUser, HolidayForm form) {
        requireHrManager(currentUser);
        if (form.holidayDate() == null || !StringUtils.hasText(form.name())) {
            throw new BusinessException("휴무일과 휴무명을 입력하세요.");
        }
        CompanyHoliday holiday = new CompanyHoliday();
        holiday.setHolidayDate(form.holidayDate());
        holiday.setName(form.name().trim());
        String category = StringUtils.hasText(form.category()) ? form.category().trim().toUpperCase() : "COMPANY";
        if (!"COMPANY".equals(category) && !"PUBLIC".equals(category)) category = "COMPANY";
        holiday.setCategory(category);
        holiday.setCreatedBy(currentUser.getUserId());
        hrMapper.upsertCompanyHoliday(holiday);
    }

    @Transactional
    public void deleteCompanyHoliday(CustomUserDetails currentUser, LocalDate holidayDate) {
        requireHrManager(currentUser);
        hrMapper.deleteCompanyHoliday(holidayDate);
    }

    /* ── [A6] 법정공휴일 일괄 생성·CSV 반입 (폐쇄망 대응) ── */

    @Transactional
    public String generatePublicHolidays(CustomUserDetails currentUser, int year) {
        requireHrManager(currentUser);
        if (!KoreanHolidays.supports(year)) {
            throw new BusinessException("내장 공휴일 데이터는 " + KoreanHolidays.supportedRange()
                    + "년만 지원합니다. 그 외 연도·임시공휴일은 CSV 반입을 사용하세요.");
        }
        int inserted = 0;
        int skipped = 0;
        for (KoreanHolidays.Holiday h : KoreanHolidays.withSubstitutes(year)) {
            if (hrMapper.findCompanyHoliday(h.date()) != null) {
                skipped++;
                continue;
            }
            CompanyHoliday holiday = new CompanyHoliday();
            holiday.setHolidayDate(h.date());
            holiday.setName(h.name());
            holiday.setCategory("PUBLIC");
            holiday.setCreatedBy(currentUser.getUserId());
            hrMapper.upsertCompanyHoliday(holiday);
            inserted++;
        }
        return year + "년 법정공휴일 " + inserted + "건 등록"
                + (skipped > 0 ? ", 이미 있는 " + skipped + "건 건너뜀" : "");
    }

    /** 임시공휴일(선거일 등)·내장 범위 밖 연도 반입 — 행 형식: yyyy-MM-dd,이름[,PUBLIC|COMPANY] */
    @Transactional
    public String importHolidaysCsv(CustomUserDetails currentUser,
                                    org.springframework.web.multipart.MultipartFile file) {
        requireHrManager(currentUser);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("CSV 파일을 선택하세요.");
        }
        int inserted = 0;
        int skipped = 0;
        List<Integer> errorLines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(file.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.strip().replace("﻿", "");
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(",");
                LocalDate date;
                try {
                    date = LocalDate.parse(parts[0].strip());
                } catch (Exception e) {
                    if (lineNo > 1) { // 1행은 헤더 허용
                        errorLines.add(lineNo);
                    }
                    continue;
                }
                String name = parts.length > 1 ? parts[1].strip() : "";
                if (name.isEmpty()) {
                    errorLines.add(lineNo);
                    continue;
                }
                String category = parts.length > 2 && "COMPANY".equalsIgnoreCase(parts[2].strip())
                        ? "COMPANY" : "PUBLIC";
                if (hrMapper.findCompanyHoliday(date) != null) {
                    skipped++;
                    continue;
                }
                CompanyHoliday holiday = new CompanyHoliday();
                holiday.setHolidayDate(date);
                holiday.setName(name);
                holiday.setCategory(category);
                holiday.setCreatedBy(currentUser.getUserId());
                hrMapper.upsertCompanyHoliday(holiday);
                inserted++;
            }
        } catch (java.io.IOException e) {
            throw new BusinessException("CSV 파일을 읽지 못했습니다: " + e.getMessage());
        }
        if (inserted == 0 && skipped == 0) {
            throw new BusinessException("반입할 수 있는 행이 없습니다. 형식: yyyy-MM-dd,이름[,PUBLIC|COMPANY]"
                    + (errorLines.isEmpty() ? "" : " (형식 오류 " + errorLines.size() + "건)"));
        }
        return "CSV 반입: " + inserted + "건 등록"
                + (skipped > 0 ? ", 중복 " + skipped + "건 건너뜀" : "")
                + (errorLines.isEmpty() ? "" : ", 형식 오류 " + errorLines.size() + "건(행 " + errorLines + ")");
    }

    @Transactional(readOnly = true)
    public List<AttendanceRecord> attendanceRecords(CustomUserDetails currentUser, LocalDate fromDate, LocalDate toDate) {
        var visibility = visibility(currentUser);
        return hrMapper.findAttendanceRecords(visibility.userId(), visibility.teamId(), fromDate, toDate);
    }

    @Transactional(readOnly = true)
    public List<AttendanceDailyStatus> attendanceDailyStatuses(CustomUserDetails currentUser, LocalDate fromDate, LocalDate toDate) {
        return attendanceDailyStatusesForUsers(fromDate, toDate, visibleUsers(currentUser));
    }

    @Transactional(readOnly = true)
    public List<AttendanceDailyStatus> allAttendanceDailyStatuses(LocalDate fromDate, LocalDate toDate) {
        return attendanceDailyStatusesForUsers(fromDate, toDate, userMapper.findAllActive().stream()
                .filter(user -> !Boolean.TRUE.equals(user.getSystemAccount()))
                .toList());
    }

    private List<AttendanceDailyStatus> attendanceDailyStatusesForUsers(LocalDate fromDate, LocalDate toDate, List<UserAccount> users) {
        Set<Long> userIds = users.stream().map(UserAccount::getId).collect(java.util.stream.Collectors.toSet());
        Map<String, AttendanceRecord> recordMap = new HashMap<>();
        hrMapper.findAttendanceRecords(null, null, fromDate, toDate).stream()
                .filter(record -> userIds.contains(record.getUserId()))
                .forEach(record -> recordMap.put(key(record.getUserId(), record.getWorkDate()), record));

        Map<LocalDate, AttendanceDayOverride> overrideMap = new HashMap<>();
        hrMapper.findAttendanceOverrides(fromDate, toDate).forEach(override -> overrideMap.put(override.getWorkDate(), override));

        Map<LocalDate, CompanyHoliday> holidayMap = new HashMap<>();
        hrMapper.findCompanyHolidays(fromDate, toDate).forEach(holiday -> holidayMap.put(holiday.getHolidayDate(), holiday));

        List<BusinessTrip> approvedTrips = hrMapper.findApprovedBusinessTrips(null, null, fromDate, toDate).stream()
                .filter(trip -> userIds.contains(trip.getUserId()))
                .toList();
        List<FlexTimeRequest> approvedFlex = hrMapper.findApprovedFlexTimes(null, null, fromDate, toDate).stream()
                .filter(flex -> userIds.contains(flex.getUserId()))
                .toList();
        List<LeaveRequest> approvedLeaves = hrMapper.findApprovedLeavesInRange(fromDate, toDate).stream()
                .filter(leave -> userIds.contains(leave.getUserId()))
                .toList();
        Map<String, AttendanceCorrection> correctionMap = new HashMap<>();
        for (int year = fromDate.getYear(); year <= toDate.getYear(); year++) {
            hrMapper.findAttendanceCorrections(null, null, year).stream()
                    .filter(correction -> userIds.contains(correction.getUserId()))
                    .filter(correction -> !correction.getWorkDate().isBefore(fromDate)
                            && !correction.getWorkDate().isAfter(toDate))
                    .forEach(correction -> correctionMap.putIfAbsent(
                            key(correction.getUserId(), correction.getWorkDate()), correction));
        }
        AttendanceSetting setting = attendanceSetting();
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        List<AttendanceDailyStatus> statuses = new ArrayList<>();
        Map<Integer, AttendanceWeekDay> week = weekSchedule();
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            AttendanceWeekDay weekDay = weekDayOf(week, date);
            // 요일 휴무(OFF)는 표시하지 않되, 전사 일자예외(특정일 근무 지정)가 있으면 근무일로 취급
            if (weekDay.isOff() && !overrideMap.containsKey(date)) {
                continue;
            }
            AttendanceDayOverride override = overrideMap.get(date);
            CompanyHoliday holiday = holidayMap.get(date);
            for (UserAccount user : users) {
                AttendanceRecord record = recordMap.get(key(user.getId(), date));
                // 입사(계정 생성) 전·퇴사 후 날짜는 판정 대상이 아니다. 실제 기록이 있으면 그대로 보여 준다.
                if (record == null && !user.isAttendanceTarget(date)) {
                    continue;
                }
                BusinessTrip trip = approvedTripForDate(approvedTrips, user.getId(), date);
                AttendanceDailyStatus status = new AttendanceDailyStatus();
                status.setUserId(user.getId());
                status.setMemberName(user.getName());
                status.setTeamId(user.getTeamId());
                status.setTeamName(user.getTeamName());
                status.setContractor(user.getContractor());
                status.setWorkDate(date);
                status.setFuture(date.isAfter(today));
                ShiftGroupDef shift = approvedShiftForDate(approvedFlex, user.getId(), date);
                WorkSchedule schedule = WorkScheduleResolver.resolve(
                        setting.getDefaultStartTime(), setting.getDefaultEndTime(), override, shift, weekDay);
                status.setStartTime(schedule.startTime());
                status.setEndTime(schedule.endTime());
                status.setScheduleNote(override == null && shift == null && !weekDay.isHalf() && !weekDay.hasCustomTimes()
                        ? "기본" : schedule.note());
                AttendanceCorrection correction = correctionMap.get(key(user.getId(), date));
                if (correction != null) {
                    status.setCorrectionId(correction.getId());
                    status.setCorrectionStatus(correction.getStatus());
                    status.setCorrectionPending(correction.getStatus() == AttendanceCorrectionStatus.PENDING);
                }
                if (record != null) {
                    status.setCheckInAt(record.getCheckInAt());
                    status.setCheckOutAt(record.getCheckOutAt());
                    status.setNote(record.getNote());
                }
                List<LeaveRequest> leavesOfDate = approvedLeavesForDate(approvedLeaves, user.getId(), date);
                LeaveRequest leave = leavesOfDate.stream().findFirst().orElse(null);
                boolean fullDayLeave = isFullDayCovered(leavesOfDate);
                if (user.isOnLeave(date)) {
                    // [B6] 휴직 기간 — 결근 판정 없이 휴직으로 표시
                    status.setStatus(AttendanceStatus.HOLIDAY);
                    status.setScheduleNote("휴직");
                    status.setNote("휴직");
                } else if (holiday != null) {
                    status.setStatus(AttendanceStatus.HOLIDAY);
                    status.setHolidayName(holiday.getName());
                    status.setNote(holiday.getName());
                } else if (fullDayLeave) {
                    // 승인된 종일 휴가(또는 오전+오후 반차) — 결근이 아니라 휴무로 표시
                    status.setStatus(AttendanceStatus.HOLIDAY);
                    String leaveLabel = fullDayLeaveLabel(leavesOfDate);
                    status.setScheduleNote(leaveLabel);
                    status.setNote(leaveLabel);
                } else if (trip != null) {
                    status.setStatus(AttendanceStatus.BUSINESS_TRIP);
                    status.setTripDestination(trip.getDestination());
                    status.setNote(trip.getDestination());
                } else if (record != null) {
                    status.setStatus(record.getStatus());
                } else if (date.isBefore(today)) {
                    status.setStatus(AttendanceStatus.ABSENT);
                } else {
                    status.setStatus(AttendanceStatus.SCHEDULED);
                }
                if (leave != null && !fullDayLeave) {
                    // 반차 — 기존 비고에 반차 종류를 병기
                    status.setNote(status.getNote() == null || status.getNote().isBlank()
                            ? leave.getTypeLabel()
                            : status.getNote() + " · " + leave.getTypeLabel());
                }
                statuses.add(status);
            }
        }
        return statuses;
    }

    @Transactional(readOnly = true)
    public List<AttendanceDailyStatus> memberAttendanceDailyStatuses(CustomUserDetails currentUser, LocalDate fromDate, LocalDate toDate) {
        Set<Long> userIds = memberScopeUserIds(currentUser);
        if (userIds.isEmpty()) {
            return List.of();
        }
        return attendanceDailyStatusesForUsers(fromDate, toDate,
                userMapper.findAllActive().stream()
                        .filter(user -> userIds.contains(user.getId()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public AttendanceRecord todayAttendance(CustomUserDetails currentUser) {
        return hrMapper.findAttendanceByUserDate(currentUser.getUserId(), LocalDate.now(SERVICE_ZONE));
    }

    /** [B2] 특정 직원의 연차 잔여(부여+조정−사용) — 결재함 판단 정보용 읽기 전용. */
    @Transactional(readOnly = true)
    public BigDecimal remainingLeaveDays(Long userId, int year) {
        UserAccount user = userMapper.findById(userId);
        BigDecimal entitlement = annualLeaveEntitlement(user == null ? null : user.getHireDate(), year,
                loaService.excludedLoaDatesByUser(year).get(userId), user == null ? null : user.getResignedOn());
        LeaveBalance balance = hrMapper.findLeaveBalance(userId, year);
        BigDecimal adjusted = balance == null ? BigDecimal.ZERO : zero(balance.getAdjustedDays());
        BigDecimal used = balance == null ? BigDecimal.ZERO : zero(balance.getUsedDays());
        return entitlement.add(adjusted).subtract(used);
    }

    /* ── [A8] 팀 부재 현황 — 결재 판단·신청 참고용 ── */

    public record TeamAbsence(Long userId, String memberName, String label,
                              LocalDate startDate, LocalDate endDate) {}

    /** 기준 직원과 같은 팀의 승인 휴가·출장 중 from~to와 겹치는 목록(본인 제외). */
    @Transactional(readOnly = true)
    public List<TeamAbsence> teamAbsences(Long userId, LocalDate from, LocalDate to) {
        UserAccount user = userMapper.findById(userId);
        if (user == null || user.getTeamId() == null || from == null || to == null) {
            return List.of();
        }
        List<TeamAbsence> absences = new ArrayList<>();
        for (LeaveRequest leave : hrMapper.findApprovedTeamLeaves(user.getTeamId(), from, to)) {
            if (leave.getUserId().equals(userId)) {
                continue;
            }
            absences.add(new TeamAbsence(leave.getUserId(), leave.getMemberName(),
                    leave.getType() == null ? "휴가" : leave.getTypeLabel(),
                    leave.getStartDate(), leave.getEndDate()));
        }
        for (BusinessTrip trip : hrMapper.findApprovedBusinessTrips(null, user.getTeamId(), from, to)) {
            if (trip.getUserId().equals(userId)) {
                continue;
            }
            absences.add(new TeamAbsence(trip.getUserId(), trip.getMemberName(),
                    "출장(" + trip.getDestination() + ")", trip.getStartDate(), trip.getEndDate()));
        }
        absences.sort(java.util.Comparator.comparing(TeamAbsence::startDate)
                .thenComparing(TeamAbsence::memberName));
        return absences;
    }

    /* ── [A5] 연차 원장·수동 조정 (V73) ── */

    /**
     * @param gradeName 등급명(연차수당 단가가 등급별이라 함께 보여 준다). 미지정이면 null.
     * @param allowance 미사용 연차수당. <b>계산할 수 없으면 null</b> — 등급 미지정이거나
     *                  그 해 단가가 미설정인 경우다. 0원과 구분해야 한다(0원은 정해서 0인 것).
     */
    public record LeaveLedgerRow(Long userId, String memberName, String teamName, LocalDate hireDate,
                                 BigDecimal entitlement, BigDecimal adjusted, BigDecimal used,
                                 BigDecimal remaining, String note,
                                 String gradeName, BigDecimal allowance,
                                 com.portfolio.worksystem.model.LeaveAllowanceRate allowanceRate,
                                 BigDecimal dailyAmount) {}

    @Transactional(readOnly = true)
    public List<LeaveLedgerRow> leaveLedger(CustomUserDetails currentUser, int year) {
        requireHrManager(currentUser);
        Map<Long, LeaveBalance> balances = new HashMap<>();
        hrMapper.findLeaveBalancesByYear(year).forEach(b -> balances.put(b.getUserId(), b));
        // [D4] 미사용 연차는 이월하지 않고 수당으로 지급한다 — 단가는 연도·등급별.
        Map<Long, BigDecimal> rates = leaveAllowanceService.rateByGrade(year);
        Map<Long, com.portfolio.worksystem.model.LeaveAllowanceRate> editableRates = new HashMap<>();
        leaveAllowanceService.ratesForYear(year).forEach(rate -> editableRates.put(rate.getGradeId(), rate));
        // [B7] 휴직(출근 간주가 아닌 것)만큼 부여를 비례 삭감한다. 한 번만 조회해 나눠 쓴다.
        Map<Long, java.util.Set<LocalDate>> loaByUser = loaService.excludedLoaDatesByUser(year);
        return userMapper.findAllActive().stream()
                .filter(user -> !Boolean.TRUE.equals(user.getSystemAccount()))
                .map(user -> {
                    LeaveBalance balance = balances.get(user.getId());
                    BigDecimal entitlement = annualLeaveEntitlement(user.getHireDate(), year,
                            loaByUser.get(user.getId()), user.getResignedOn());
                    BigDecimal adjusted = balance == null ? BigDecimal.ZERO : zero(balance.getAdjustedDays());
                    BigDecimal used = balance == null ? BigDecimal.ZERO : zero(balance.getUsedDays());
                    BigDecimal remaining = entitlement.add(adjusted).subtract(used);
                    return new LeaveLedgerRow(user.getId(), user.getName(), user.getTeamName(), user.getHireDate(),
                            entitlement, adjusted, used, remaining,
                            balance == null ? null : balance.getNote(),
                            user.getGradeName(),
                            leaveAllowanceService.allowanceFor(user.getGradeId(), remaining, rates),
                            editableRates.get(user.getGradeId()), rates.get(user.getGradeId()));
                })
                .sorted(java.util.Comparator
                        .comparing(LeaveLedgerRow::teamName, java.util.Comparator.nullsLast(String::compareTo))
                        .thenComparing(LeaveLedgerRow::memberName))
                .toList();
    }

    /** [A5] 연차 수동 조정 — ± 일수, 사유 필수, 이력 보존(leave_balance_adjustments). */
    @Transactional
    public void adjustLeaveBalance(CustomUserDetails currentUser, Long userId, int year,
                                   BigDecimal deltaDays, String reason) {
        requireHrManager(currentUser);
        if (userId == null || userMapper.findById(userId) == null) {
            throw new BusinessException("대상 직원을 찾을 수 없습니다.");
        }
        if (deltaDays == null || deltaDays.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("조정 일수를 입력하세요(예: +1, -0.5).");
        }
        if (deltaDays.abs().compareTo(new BigDecimal("50")) > 0) {
            throw new BusinessException("조정 일수는 한 번에 ±50일을 넘을 수 없습니다.");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("조정 사유를 입력하세요.");
        }
        ensureBalance(userId, year);
        hrMapper.increaseAdjustedLeave(userId, year, deltaDays);
        com.portfolio.worksystem.model.LeaveBalanceAdjustment adjustment =
                new com.portfolio.worksystem.model.LeaveBalanceAdjustment();
        adjustment.setUserId(userId);
        adjustment.setBalanceYear(year);
        adjustment.setDeltaDays(deltaDays);
        adjustment.setReason(reason.trim());
        adjustment.setAdjustedBy(currentUser.getUserId());
        hrMapper.insertLeaveBalanceAdjustment(adjustment);
    }

    @Transactional(readOnly = true)
    public List<com.portfolio.worksystem.model.LeaveBalanceAdjustment> leaveAdjustments(Long userId, int year) {
        return hrMapper.findLeaveBalanceAdjustments(userId, year);
    }

    /* ── [A4] 근태 월 마감 (V74) ── */

    @Transactional(readOnly = true)
    public boolean isMonthLocked(LocalDate date) {
        if (date == null) {
            return false;
        }
        com.portfolio.worksystem.model.AttendanceMonthLockEvent event =
                hrMapper.findLatestMonthLockEvent(java.time.YearMonth.from(date).toString());
        return event != null && "LOCK".equals(event.getAction());
    }

    /** 마감 월이면 차단 — 정정 신청/승인·직권 정정·소급 결재 반영 공용 가드. */
    private void requireMonthOpen(LocalDate date, String actionLabel) {
        if (isMonthLocked(date)) {
            throw new BusinessException(java.time.YearMonth.from(date) + "월은 근태 마감되어 "
                    + actionLabel + "할 수 없습니다. 먼저 마감을 해제하세요.");
        }
    }

    private void requireLeaveMonthsOpen(LeaveRequest request, String actionLabel) {
        for (java.time.YearMonth month = java.time.YearMonth.from(request.getStartDate());
             !month.isAfter(java.time.YearMonth.from(request.getEndDate())); month = month.plusMonths(1)) {
            requireMonthOpen(month.atDay(1), actionLabel);
        }
    }

    public record MonthLockPreview(String ym, boolean locked, int absentCount,
                                   int pendingCorrections, int pendingLeaves) {}

    /** 마감 전 미처리 현황 — 결근·미결 정정·미결 휴가 결재 수(경고용). */
    @Transactional(readOnly = true)
    public MonthLockPreview monthLockPreview(CustomUserDetails currentUser, java.time.YearMonth ym) {
        requireHrManager(currentUser);
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        int absent = (int) allAttendanceDailyStatuses(first, last).stream()
                .filter(s -> s.getStatus() == AttendanceStatus.ABSENT)
                .count();
        int pendingCorrections = hrMapper.findPendingCorrections(null, null, first, last).size();
        int pendingLeaves = (int) hrMapper.findLeaveRequests(null, null, ym.getYear()).stream()
                .filter(r -> r.isPending() || r.getStatus() == LeaveStatus.CANCEL_PENDING)
                .filter(r -> r.getStartDate() != null && r.getEndDate() != null
                        && !r.getStartDate().isAfter(last) && !r.getEndDate().isBefore(first))
                .count();
        return new MonthLockPreview(ym.toString(), isMonthLocked(first), absent, pendingCorrections, pendingLeaves);
    }

    @Transactional
    public void lockMonth(CustomUserDetails currentUser, java.time.YearMonth ym) {
        requireHrManager(currentUser);
        if (ym.isAfter(java.time.YearMonth.now(SERVICE_ZONE))) {
            throw new BusinessException("미래 월은 마감할 수 없습니다.");
        }
        if (isMonthLocked(ym.atDay(1))) {
            throw new BusinessException("이미 마감된 월입니다: " + ym);
        }
        insertMonthLockEvent(ym, "LOCK", currentUser.getUserId());
    }

    @Transactional
    public void unlockMonth(CustomUserDetails currentUser, java.time.YearMonth ym) {
        requireHrManager(currentUser);
        if (!isMonthLocked(ym.atDay(1))) {
            throw new BusinessException("마감 상태가 아닌 월입니다: " + ym);
        }
        insertMonthLockEvent(ym, "UNLOCK", currentUser.getUserId());
    }

    private void insertMonthLockEvent(java.time.YearMonth ym, String action, Long actedBy) {
        com.portfolio.worksystem.model.AttendanceMonthLockEvent event =
                new com.portfolio.worksystem.model.AttendanceMonthLockEvent();
        event.setYm(ym.toString());
        event.setAction(action);
        event.setActedBy(actedBy);
        hrMapper.insertMonthLockEvent(event);
    }

    @Transactional(readOnly = true)
    public List<String> lockedMonths() {
        return hrMapper.findLockedMonths();
    }

    @Transactional(readOnly = true)
    public List<com.portfolio.worksystem.model.AttendanceMonthLockEvent> monthLockEvents() {
        return hrMapper.findMonthLockEvents();
    }

    /** [A3] HR 직권 근태 정정 — 관리자 매트릭스에서 직접 수정. 사유 필수, 전/후 값 감사 로그(V72). */
    @Transactional
    public void adminAdjustAttendance(CustomUserDetails currentUser, Long userId, LocalDate workDate,
                                      LocalTime checkIn, LocalTime checkOut, AttendanceStatus status, String reason) {
        requireHrManager(currentUser);
        if (userId == null || workDate == null || status == null) {
            throw new BusinessException("대상 직원·일자·상태를 확인하세요.");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("직권 정정 사유를 입력하세요.");
        }
        if (userMapper.findById(userId) == null) {
            throw new BusinessException("대상 직원을 찾을 수 없습니다.");
        }
        if (workDate.isAfter(LocalDate.now(SERVICE_ZONE))) {
            throw new BusinessException("미래 일자는 정정할 수 없습니다.");
        }
        requireMonthOpen(workDate, "직권 정정"); // [A4]
        java.util.EnumSet<AttendanceStatus> allowed = java.util.EnumSet.of(AttendanceStatus.NORMAL,
                AttendanceStatus.LATE, AttendanceStatus.EARLY_LEAVE, AttendanceStatus.ABSENT,
                AttendanceStatus.REMOTE, AttendanceStatus.OUTSIDE);
        if (!allowed.contains(status)) {
            throw new BusinessException("직권 정정으로 지정할 수 없는 상태입니다.");
        }
        if (checkIn != null && checkOut != null && !checkIn.isBefore(checkOut)) {
            throw new BusinessException("퇴근 시각은 출근 시각보다 늦어야 합니다.");
        }
        LocalDateTime inAt = checkIn == null ? null : LocalDateTime.of(workDate, checkIn);
        LocalDateTime outAt = checkOut == null ? null : LocalDateTime.of(workDate, checkOut);
        AttendanceRecord existing = hrMapper.findAttendanceByUserDate(userId, workDate);
        com.portfolio.worksystem.model.AttendanceAdminAdjustment log = new com.portfolio.worksystem.model.AttendanceAdminAdjustment();
        log.setUserId(userId);
        log.setWorkDate(workDate);
        if (existing != null) {
            log.setBeforeCheckInAt(existing.getCheckInAt());
            log.setBeforeCheckOutAt(existing.getCheckOutAt());
            log.setBeforeStatus(existing.getStatus());
            hrMapper.updateAttendanceRecord(existing.getId(), inAt, outAt, status);
        } else {
            AttendanceRecord record = new AttendanceRecord();
            record.setUserId(userId);
            record.setWorkDate(workDate);
            record.setCheckInAt(inAt);
            record.setCheckOutAt(outAt);
            record.setStatus(status);
            record.setSource("CORRECTION");
            hrMapper.insertAttendance(record);
        }
        log.setAfterCheckInAt(inAt);
        log.setAfterCheckOutAt(outAt);
        log.setAfterStatus(status);
        log.setReason(reason.trim());
        log.setAdjustedBy(currentUser.getUserId());
        hrMapper.insertAttendanceAdminAdjustment(log);
        panelRefreshPublisher.publishAllActive("attendance-admin-adjust");
    }

    /** [A1] 오늘 출근 체크가 불가한 사유(없으면 null) — 웹 버튼 비활성 안내와 checkIn 가드가 공유. */
    @Transactional(readOnly = true)
    public String todayCheckBlockReason(Long userId) {
        return todayCheckBlockReasonOn(userId, LocalDate.now(SERVICE_ZONE));
    }

    /** [OW] 그날 출근 자체가 성립하지 않는 사유(휴직·승인 출장·종일 휴가) — 없으면 null. */
    @Transactional(readOnly = true)
    public String todayCheckBlockReasonOn(Long userId, LocalDate today) {
        UserAccount account = userMapper.findById(userId);
        if (account != null && account.isOnLeave(today)) {
            return "휴직 기간에는 출근 처리하지 않습니다."; // [B6]
        }
        if (approvedTripForDate(hrMapper.findApprovedBusinessTrips(userId, null, today, today), userId, today) != null) {
            return "승인된 출장일은 출장으로 처리됩니다.";
        }
        LeaveRequest todayLeave = hrMapper.findApprovedLeaveForDate(userId, today);
        if (isFullDayLeave(todayLeave)) {
            return "승인된 휴가일(" + todayLeave.getTypeLabel()
                    + ")은 출근 처리하지 않습니다. 출근이 필요하면 휴가 취소 결재를 먼저 진행하세요.";
        }
        return null;
    }

    /**
     * [OT] 지금 누르는 출근이 정규 근무가 아니라 <b>시간외 기록</b>으로 남아야 하는 사유(정규 근무면 null).
     *
     * <p>휴무일·휴무 요일, 출근 기록 없이 기준 퇴근시각을 넘긴 때, 그날 퇴근까지 마친 뒤의 재출근이 여기 해당한다.
     * 사전 신청이 원칙이지만 급한 일로 그냥 나온 경우의 출퇴근이 남아 있어야 나중에 증적과 함께 신청할 수 있다.
     * 출근 자체가 성립하지 않는 날(휴직·출장·종일 휴가)은 {@link #todayCheckBlockReason} 이 먼저 막는다.
     */
    @Transactional(readOnly = true)
    public String todayOvertimeModeReason(Long userId) {
        if (todayCheckBlockReason(userId) != null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        LocalDate today = now.toLocalDate();
        if (hrMapper.findCompanyHoliday(today) != null) {
            return "회사 휴무일";
        }
        if (weekDayOf(weekSchedule(), today).isOff() && hrMapper.findAttendanceOverride(today) == null) {
            return "휴무 요일";
        }
        AttendanceRecord todayRecord = hrMapper.findAttendanceByUserDate(userId, today);
        if (todayRecord != null && todayRecord.getCheckInAt() != null) {
            return todayRecord.getCheckOutAt() != null ? "퇴근 후 재출근" : null;
        }
        LeaveRequest todayLeave = hrMapper.findApprovedLeaveForDate(userId, today);
        return checkInClosedReason(now, workSchedule(userId, today), todayLeave) != null ? "기준 퇴근시각 이후" : null;
    }

    @Transactional
    public void checkIn(CustomUserDetails currentUser) {
        checkIn(currentUser, null);
    }

    /** @param source 기록 경로(WEB/DESKTOP/DEVICE) — null 이면 WEB. */
    @Transactional
    public void checkIn(CustomUserDetails currentUser, String source) {
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        String blockReason = todayCheckBlockReason(currentUser.getUserId());
        if (blockReason != null) {
            throw new BusinessException(blockReason);
        }
        LeaveRequest todayLeave = hrMapper.findApprovedLeaveForDate(currentUser.getUserId(), today);
        AttendanceRecord existing = hrMapper.findAttendanceByUserDate(currentUser.getUserId(), today);
        if (existing != null && existing.getCheckInAt() != null) {
            throw new BusinessException("이미 출근 처리되었습니다.");
        }
        if (hrMapper.findCompanyHoliday(today) != null
                || (weekDayOf(weekSchedule(), today).isOff() && hrMapper.findAttendanceOverride(today) == null)) {
            throw new BusinessException("휴무일 출근은 시간외 기록으로 남깁니다. 화면을 새로 고친 뒤 다시 시도하세요.");
        }
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        WorkSchedule schedule = workSchedule(currentUser.getUserId(), today);
        String closedReason = checkInClosedReason(now, schedule, todayLeave);
        if (closedReason != null) {
            throw new BusinessException(closedReason);
        }
        AttendanceRecord record = new AttendanceRecord();
        record.setUserId(currentUser.getUserId());
        record.setWorkDate(today);
        record.setCheckInAt(now);
        record.setStatus(now.toLocalTime().isAfter(judgmentStartTime(schedule, todayLeave)) ? AttendanceStatus.LATE : AttendanceStatus.NORMAL);
        record.setSource(source);
        hrMapper.insertAttendance(record);
        panelRefreshPublisher.publishAllActive("attendance-check-in");
    }

    @Transactional
    public void checkOut(CustomUserDetails currentUser) {
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        AttendanceRecord open = openAttendanceForCheckout(currentUser.getUserId(), now.toLocalDate());
        if (open == null) {
            throw new BusinessException("퇴근할 출근 기록이 없습니다.");
        }
        // [UX18] 미퇴근 기록의 근무일 기준으로 판정 — 자정 넘긴 야근이면 조퇴 아님(연장근무)
        LocalDate workDate = open.getWorkDate();
        WorkSchedule schedule = workSchedule(currentUser.getUserId(), workDate);
        LeaveRequest leave = hrMapper.findApprovedLeaveForDate(currentUser.getUserId(), workDate);
        AttendanceStatus status = now.toLocalDate().equals(workDate)
                && now.toLocalTime().isBefore(judgmentEndTime(schedule, leave))
                ? AttendanceStatus.EARLY_LEAVE : open.getStatus();
        hrMapper.updateAttendanceCheckOut(open.getId(), now, status);
        panelRefreshPublisher.publishAllActive("attendance-check-out");
    }

    /**
     * [UX18] 퇴근 대상 기록 — 오늘 미퇴근 기록 우선, 없으면 전일 야근 미퇴근 기록을
     * "그 근무일의 다음날 기준 출근시각 전"까지만 허용(그 이후는 정정 대상). 없으면 null.
     */
    private AttendanceRecord openAttendanceForCheckout(Long userId, LocalDate today) {
        AttendanceRecord todayRec = hrMapper.findAttendanceByUserDate(userId, today);
        if (todayRec != null && todayRec.getCheckInAt() != null && todayRec.getCheckOutAt() == null) {
            return todayRec;
        }
        AttendanceRecord open = hrMapper.findLatestOpenAttendance(userId);
        if (open == null) {
            return null;
        }
        LocalDate nextDay = open.getWorkDate().plusDays(1);
        LocalTime nextStart = workSchedule(userId, nextDay).startTime();
        LocalDateTime deadline = LocalDateTime.of(nextDay, nextStart);
        if (LocalDateTime.now(SERVICE_ZONE).isAfter(deadline)) {
            return null; // 다음 근무일 출근시각을 지나침 → 정정 신청 유도
        }
        return open;
    }

    /**
     * [UX20] 지금 출/퇴근을 찍으면 어떤 판정(정상/지각/조퇴)이 되는지 미리보기.
     * kind: "in"=출근, "out"=퇴근. 확인 모달에 표시.
     */
    @Transactional(readOnly = true)
    public CheckPreview checkPreview(Long userId, String kind) {
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        if ("out".equals(kind)) {
            AttendanceRecord open = openAttendanceForCheckout(userId, now.toLocalDate());
            if (open == null) {
                AttendanceRecord today = hrMapper.findAttendanceByUserDate(userId, now.toLocalDate());
                if (today != null && today.getCheckOutAt() != null) {
                    return new CheckPreview(false, "이미 퇴근 처리되었습니다.", null);
                }
                return new CheckPreview(false, "퇴근할 출근 기록이 없습니다.", null);
            }
            LocalDate workDate = open.getWorkDate();
            WorkSchedule schedule = workSchedule(userId, workDate);
            LeaveRequest leave = hrMapper.findApprovedLeaveForDate(userId, workDate);
            boolean early = now.toLocalDate().equals(workDate)
                    && now.toLocalTime().isBefore(judgmentEndTime(schedule, leave));
            String label = early ? "조퇴" : (now.toLocalDate().equals(workDate) ? "정상 퇴근" : "연장/야근 퇴근");
            return new CheckPreview(true, label, now.format(TIME_LABEL_FMT));
        }
        // 출근
        String block = todayCheckBlockReason(userId);
        if (block != null) {
            return new CheckPreview(false, block, null);
        }
        LocalDate today = now.toLocalDate();
        AttendanceRecord rec = hrMapper.findAttendanceByUserDate(userId, today);
        if (rec != null && rec.getCheckInAt() != null) {
            return new CheckPreview(false, "이미 출근 처리되었습니다.", null);
        }
        WorkSchedule schedule = workSchedule(userId, today);
        LeaveRequest leave = hrMapper.findApprovedLeaveForDate(userId, today);
        String closedReason = checkInClosedReason(now, schedule, leave);
        if (closedReason != null) {
            return new CheckPreview(false, closedReason, null);
        }
        boolean late = now.toLocalTime().isAfter(judgmentStartTime(schedule, leave));
        return new CheckPreview(true, late ? "지각" : "정상 출근", now.format(TIME_LABEL_FMT));
    }

    public record CheckPreview(boolean allowed, String label, String nowTime) {}

    /**
     * 기준 퇴근시각이 지난 뒤의 출근 체크는 받지 않는다(없으면 null).
     * 근무가 끝난 시각에 찍힌 출근을 '지각'으로 남기면 실제 근무와 맞지 않는 기록이 된다 — 정정 신청으로 남기게 한다.
     */
    private String checkInClosedReason(LocalDateTime now, WorkSchedule schedule, LeaveRequest leave) {
        LocalTime end = judgmentEndTime(schedule, leave);
        if (end == null || !now.toLocalTime().isAfter(end)) {
            return null;
        }
        return "기준 퇴근시각(" + end.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                + ")이 지나 출근 체크를 할 수 없습니다. 실제 근무했다면 근태 정정을 신청하세요.";
    }

    /**
     * [UX19] 웹 진입 시 근태 미체크 알림 — 근무일에 출근 미체크(기준시각 근처/경과)거나
     * 기준 퇴근시각이 지났는데 미퇴근이면 안내 문구 반환(없으면 null). 휴무/휴가/출장일 제외.
     */
    public record AttendanceReminder(String kind, String message) {}

    @Transactional(readOnly = true)
    public AttendanceReminder attendanceReminder(Long userId) {
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        LocalDate today = now.toLocalDate();
        // 전일 야근 미퇴근이 아직 퇴근 가능 구간이면 우선 안내
        AttendanceRecord open = hrMapper.findLatestOpenAttendance(userId);
        if (open != null && !open.getWorkDate().equals(today)) {
            LocalDate nextDay = open.getWorkDate().plusDays(1);
            LocalDateTime deadline = LocalDateTime.of(nextDay, workSchedule(userId, nextDay).startTime());
            if (now.isBefore(deadline)) {
                return new AttendanceReminder("checkout",
                        open.getWorkDate() + " 근무의 퇴근이 아직 기록되지 않았습니다. 지금 퇴근 체크할 수 있습니다.");
            }
        }
        // 오늘이 근무일이 아니면(휴무/공휴일/휴무요일/휴가/출장) 알림 없음. 시간외 기록 대상인 때도 정규 근태 알림은 없다.
        if (todayCheckBlockReason(userId) != null || todayOvertimeModeReason(userId) != null) {
            return null;
        }
        WorkSchedule schedule = workSchedule(userId, today);
        AttendanceRecord rec = hrMapper.findAttendanceByUserDate(userId, today);
        if (rec == null || rec.getCheckInAt() == null) {
            // 기준 출근시각을 지났는데 미출근이면 안내
            if (now.toLocalTime().isAfter(schedule.startTime()) && !now.toLocalTime().isAfter(schedule.endTime())) {
                return new AttendanceReminder("checkin", "오늘 출근이 아직 기록되지 않았습니다. 출근 체크를 잊지 않으셨나요?");
            }
            return null;
        }
        if (rec.getCheckOutAt() == null && now.toLocalTime().isAfter(schedule.endTime())) {
            return new AttendanceReminder("checkout", "기준 퇴근시간이 지났는데 퇴근이 기록되지 않았습니다. 퇴근 체크를 잊지 않으셨나요?");
        }
        return null;
    }

    private static final java.time.format.DateTimeFormatter TIME_LABEL_FMT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    @Transactional(readOnly = true)
    public List<BusinessTrip> businessTrips(CustomUserDetails currentUser, Integer year) {
        var visibility = visibility(currentUser);
        return hrMapper.findBusinessTrips(visibility.userId(), visibility.teamId(), year == null ? currentYear() : year);
    }

    @Transactional(readOnly = true)
    public List<BusinessTrip> allBusinessTrips(Integer year) {
        return hrMapper.findBusinessTrips(null, null, year == null ? currentYear() : year);
    }

    @Transactional
    public void requestBusinessTrip(CustomUserDetails currentUser, BusinessTripForm form) {
        if (form.getStartDate() == null || form.getEndDate() == null) {
            throw new BusinessException("출장 시작일과 종료일을 입력하세요.");
        }
        if (form.getStartDate().isAfter(form.getEndDate())) {
            throw new BusinessException("출장 종료일은 시작일보다 빠를 수 없습니다.");
        }
        if (!StringUtils.hasText(form.getDestination())) {
            throw new BusinessException("출장지를 입력하세요.");
        }
        requireApprovalRoute(currentUser, ApprovalDocumentType.TRIP, form.getStartDate(), form.getEndDate());
        List<BusinessTripExpense> expenses = normalizeTripExpenses(form.getExpenses());
        Map<BusinessTripExpenseType, BigDecimal> totals = tripExpenseTotals(expenses);
        BusinessTrip trip = new BusinessTrip();
        trip.setUserId(currentUser.getUserId());
        trip.setStartDate(form.getStartDate());
        trip.setEndDate(form.getEndDate());
        trip.setDestination(form.getDestination().trim());
        trip.setPurpose(trimToNull(form.getPurpose()));
        trip.setTransportCost(totals.getOrDefault(BusinessTripExpenseType.TRANSPORT, BigDecimal.ZERO));
        trip.setLodgingCost(totals.getOrDefault(BusinessTripExpenseType.LODGING, BigDecimal.ZERO));
        trip.setMealCost(totals.getOrDefault(BusinessTripExpenseType.MEAL, BigDecimal.ZERO));
        trip.setDailyAllowance(totals.getOrDefault(BusinessTripExpenseType.DAILY, BigDecimal.ZERO));
        trip.setEtcCost(totals.getOrDefault(BusinessTripExpenseType.ETC, BigDecimal.ZERO));
        trip.setCostNote(expenses.stream()
                .map(BusinessTripExpense::getNote)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null));
        trip.setStatus(BusinessTripStatus.PENDING);
        trip.setCurrentApprovalStep(1);
        hrMapper.insertBusinessTrip(trip);
        for (int i = 0; i < expenses.size(); i++) {
            BusinessTripExpense expense = expenses.get(i);
            expense.setTripId(trip.getId());
            expense.setDisplayOrder(i + 1);
            hrMapper.insertBusinessTripExpense(expense);
        }
        storeTripReceiptIfPresent(currentUser, trip.getId(), form.getReceiptFile());
        List<ApprovalStep> steps = approvalService.createSteps(ApprovalDocumentType.TRIP, trip.getId(), currentUser.getUserId(), form.getStartDate(), form.getEndDate());
        if (steps.isEmpty()) {
            throw new BusinessException("출장 결재경로가 설정되어 있지 않습니다.");
        }
        approvalService.attachDelegation(currentUser.getUserId(), form.getDelegateUserId(), form.getStartDate(), form.getEndDate(),
                "출장", ApprovalDocumentType.TRIP, trip.getId());
        hrMapper.updateBusinessTripCurrentStep(trip.getId(), steps.get(0).getStepOrder());
        afterSubmit(steps, currentUser, ApprovalDocumentType.TRIP, trip.getId(),
                () -> approveBusinessTrip(currentUser, trip.getId()));
    }

    @Transactional
    public void approveBusinessTrip(CustomUserDetails currentUser, Long tripId) {
        BusinessTrip trip = getBusinessTrip(tripId);
        if (!trip.isPending()) {
            throw new BusinessException("승인 대기 상태의 출장만 승인할 수 있습니다.");
        }
        // [A4] 마감 월로의 소급 결재 차단
        requireMonthOpen(trip.getStartDate(), "출장 결재를 반영");
        requireMonthOpen(trip.getEndDate(), "출장 결재를 반영");
        ApprovalStep actedStep = approvalService.approvePendingStep(ApprovalDocumentType.TRIP, tripId, currentUser);
        notifyPostApprovalNeeded(actedStep, currentUser.getUserId(), ApprovalDocumentType.TRIP,
                tripId, trip.getUserId(), trip.getMemberName());
        ApprovalStep nextStep = approvalService.pendingStep(ApprovalDocumentType.TRIP, tripId);
        if (nextStep != null) {
            hrMapper.updateBusinessTripCurrentStep(tripId, nextStep.getStepOrder());
            notifyTurn(nextStep, currentUser.getUserId(), ApprovalDocumentType.TRIP,
                    tripId, trip.getMemberName(), trip.getUserId());
            return;
        }
        hrMapper.updateBusinessTripStatus(tripId, BusinessTripStatus.APPROVED, currentUser.getUserId(), null);
        approvalService.activateLinkedDelegation(ApprovalDocumentType.TRIP, tripId);
        storeFinalDocumentSnapshot(ApprovalDocumentType.TRIP, tripId, currentUser.getUserId());
        notifyDecided(trip.getUserId(), currentUser.getUserId(), ApprovalDocumentType.TRIP,
                tripId, true, null);
    }

    @Transactional
    public void rejectBusinessTrip(CustomUserDetails currentUser, Long tripId, String reason) {
        BusinessTrip trip = getBusinessTrip(tripId);
        if (!trip.isPending()) {
            throw new BusinessException("승인 대기 상태의 출장만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.TRIP, tripId, currentUser, reason);
        hrMapper.updateBusinessTripStatus(tripId, BusinessTripStatus.REJECTED, currentUser.getUserId(), trimToNull(reason));
        approvalService.dropLinkedDelegation(ApprovalDocumentType.TRIP, tripId);
        storeFinalDocumentSnapshot(ApprovalDocumentType.TRIP, tripId, currentUser.getUserId());
        notifyDecided(trip.getUserId(), currentUser.getUserId(), ApprovalDocumentType.TRIP,
                tripId, false, reason);
    }

    @Transactional
    public void cancelBusinessTrip(CustomUserDetails currentUser, Long tripId) {
        BusinessTrip trip = getBusinessTrip(tripId);
        if (!trip.getUserId().equals(currentUser.getUserId()) && !canManageHr(currentUser)) {
            throw new BusinessException("본인 출장 신청만 취소할 수 있습니다.");
        }
        if (!trip.isPending()) {
            throw new BusinessException("승인 대기 상태의 출장만 취소할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.TRIP, tripId);
        hrMapper.updateBusinessTripStatus(tripId, BusinessTripStatus.CANCELED, currentUser.getUserId(), null);
        approvalService.dropLinkedDelegation(ApprovalDocumentType.TRIP, tripId);
    }

    /* ================= [UX47] 출장보고서 — 출장신청서와 세트, 별도 결재 ================= */

    @Transactional(readOnly = true)
    public com.portfolio.worksystem.model.BusinessTripReport tripReportForTrip(Long tripId) {
        return tripReportMapper.findByTripId(tripId);
    }

    @Transactional(readOnly = true)
    public Map<Long, com.portfolio.worksystem.model.BusinessTripReport> tripReportsByTripIds(List<Long> tripIds) {
        if (tripIds == null || tripIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, com.portfolio.worksystem.model.BusinessTripReport> map = new HashMap<>();
        for (com.portfolio.worksystem.model.BusinessTripReport r : tripReportMapper.findByTripIds(tripIds)) {
            map.put(r.getTripId(), r);
        }
        return map;
    }

    @Transactional
    public void requestTripReport(CustomUserDetails currentUser, Long tripId,
                                  com.portfolio.worksystem.dto.TripReportForm form,
                                  List<MultipartFile> files) {
        BusinessTrip trip = getBusinessTrip(tripId);
        if (!trip.getUserId().equals(currentUser.getUserId())) {
            throw new BusinessException("본인 출장의 보고서만 작성할 수 있습니다.");
        }
        if (trip.getStatus() != BusinessTripStatus.APPROVED) {
            throw new BusinessException("결재 완료된 출장만 보고서를 작성할 수 있습니다.");
        }
        if (!StringUtils.hasText(form.getContent())) {
            throw new BusinessException("출장 내용을 입력하세요.");
        }
        com.portfolio.worksystem.model.BusinessTripReport existing = tripReportMapper.findByTripId(tripId);
        if (existing != null) {
            if (existing.getStatus() == BusinessTripStatus.PENDING || existing.getStatus() == BusinessTripStatus.APPROVED) {
                throw new BusinessException("이미 작성된 출장보고서가 있습니다.");
            }
            purgeTripReport(existing.getId()); // 반려/회수 보고서는 삭제 후 재작성
        }
        requireApprovalRoute(currentUser, ApprovalDocumentType.TRIP_REPORT, trip.getStartDate(), trip.getEndDate());
        com.portfolio.worksystem.model.BusinessTripReport report = new com.portfolio.worksystem.model.BusinessTripReport();
        report.setTripId(tripId);
        report.setContent(form.getContent().trim());
        report.setExpenseChanged(form.isExpenseChanged());
        report.setStatus(BusinessTripStatus.PENDING);
        report.setCurrentApprovalStep(1);
        tripReportMapper.insertReport(report);
        // 경비: 변동이면 폼 입력값, 동일이면 신청 경비를 그대로 복사(문서 비교·불변 보존)
        List<BusinessTripExpense> source = form.isExpenseChanged()
                ? normalizeTripExpenses(form.getExpenses())
                : hrMapper.findBusinessTripExpenses(tripId);
        if (form.isExpenseChanged() && source.isEmpty()) {
            throw new BusinessException("변동된 경비 내역을 입력하세요.");
        }
        int order = 1;
        for (BusinessTripExpense e : source) {
            com.portfolio.worksystem.model.BusinessTripReportExpense re = new com.portfolio.worksystem.model.BusinessTripReportExpense();
            re.setReportId(report.getId());
            re.setExpenseType(e.getExpenseType());
            re.setDescription(e.getDescription());
            re.setAmount(e.getAmount());
            re.setNote(e.getNote());
            re.setDisplayOrder(order++);
            tripReportMapper.insertExpense(re);
        }
        storeTripReportFiles(report.getId(), files);
        List<ApprovalStep> steps = approvalService.createSteps(ApprovalDocumentType.TRIP_REPORT, report.getId(),
                currentUser.getUserId(), trip.getStartDate(), trip.getEndDate());
        if (steps.isEmpty()) {
            throw new BusinessException("출장보고 결재경로가 설정되어 있지 않습니다.");
        }
        tripReportMapper.updateCurrentStep(report.getId(), steps.get(0).getStepOrder());
        afterSubmit(steps, currentUser, ApprovalDocumentType.TRIP_REPORT, report.getId(),
                () -> approveTripReport(currentUser, report.getId()));
    }

    @Transactional
    public void approveTripReport(CustomUserDetails currentUser, Long reportId) {
        com.portfolio.worksystem.model.BusinessTripReport report = getTripReport(reportId);
        if (!report.isPending()) {
            throw new BusinessException("승인 대기 상태의 출장보고서만 승인할 수 있습니다.");
        }
        ApprovalStep actedStep = approvalService.approvePendingStep(ApprovalDocumentType.TRIP_REPORT, reportId, currentUser);
        notifyPostApprovalNeeded(actedStep, currentUser.getUserId(), ApprovalDocumentType.TRIP_REPORT,
                reportId, report.getUserId(), report.getMemberName());
        ApprovalStep nextStep = approvalService.pendingStep(ApprovalDocumentType.TRIP_REPORT, reportId);
        if (nextStep != null) {
            tripReportMapper.updateCurrentStep(reportId, nextStep.getStepOrder());
            notifyTurn(nextStep, currentUser.getUserId(), ApprovalDocumentType.TRIP_REPORT,
                    reportId, report.getMemberName(), report.getUserId());
            return;
        }
        tripReportMapper.updateStatus(reportId, BusinessTripStatus.APPROVED.name(), currentUser.getUserId(), null);
        storeFinalDocumentSnapshot(ApprovalDocumentType.TRIP_REPORT, reportId, currentUser.getUserId());
        notifyDecided(report.getUserId(), currentUser.getUserId(), ApprovalDocumentType.TRIP_REPORT,
                reportId, true, null);
    }

    @Transactional
    public void rejectTripReport(CustomUserDetails currentUser, Long reportId, String reason) {
        com.portfolio.worksystem.model.BusinessTripReport report = getTripReport(reportId);
        if (!report.isPending()) {
            throw new BusinessException("승인 대기 상태의 출장보고서만 반려할 수 있습니다.");
        }
        approvalService.rejectPendingStep(ApprovalDocumentType.TRIP_REPORT, reportId, currentUser, reason);
        tripReportMapper.updateStatus(reportId, BusinessTripStatus.REJECTED.name(), currentUser.getUserId(), trimToNull(reason));
        storeFinalDocumentSnapshot(ApprovalDocumentType.TRIP_REPORT, reportId, currentUser.getUserId());
        notifyDecided(report.getUserId(), currentUser.getUserId(), ApprovalDocumentType.TRIP_REPORT,
                reportId, false, reason);
    }

    @Transactional
    public void cancelTripReport(CustomUserDetails currentUser, Long reportId) {
        com.portfolio.worksystem.model.BusinessTripReport report = getTripReport(reportId);
        if (!report.getUserId().equals(currentUser.getUserId()) && !canManageHr(currentUser)) {
            throw new BusinessException("본인 출장보고서만 회수할 수 있습니다.");
        }
        if (!report.isPending()) {
            throw new BusinessException("승인 대기 상태의 출장보고서만 회수할 수 있습니다.");
        }
        approvalService.deleteSteps(ApprovalDocumentType.TRIP_REPORT, reportId);
        tripReportMapper.updateStatus(reportId, BusinessTripStatus.CANCELED.name(), currentUser.getUserId(), null);
    }

    @Transactional(readOnly = true)
    public List<com.portfolio.worksystem.model.BusinessTripReportExpense> tripReportExpenses(Long reportId) {
        return tripReportMapper.findExpenses(reportId);
    }

    @Transactional(readOnly = true)
    public List<com.portfolio.worksystem.model.BusinessTripReportFile> tripReportFiles(Long reportId) {
        return tripReportMapper.findFiles(reportId);
    }

    @Transactional(readOnly = true)
    public List<BusinessTripExpense> tripExpenses(Long tripId) {
        return hrMapper.findBusinessTripExpenses(tripId);
    }

    @Transactional(readOnly = true)
    public StoredBusinessTripReceipt loadTripReportFile(CustomUserDetails currentUser, Long fileId) {
        com.portfolio.worksystem.model.BusinessTripReportFile stored = tripReportMapper.findFileById(fileId);
        if (stored == null) {
            throw new BusinessException("첨부 파일을 찾을 수 없습니다.");
        }
        tripReportDocument(currentUser, stored.getReportId()); // 열람 권한 검증
        Path root = tripReceiptRoot();
        Path file = root.resolve(stored.getStoredFilename()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BusinessException("첨부 파일을 찾을 수 없습니다.");
        }
        try {
            return new StoredBusinessTripReceipt(new UrlResource(file.toUri()),
                    MediaType.parseMediaType(stored.getContentType()), stored.getOriginalFilename());
        } catch (IOException e) {
            throw new BusinessException("첨부 파일을 읽을 수 없습니다.");
        }
    }

    private com.portfolio.worksystem.model.BusinessTripReport getTripReport(Long id) {
        com.portfolio.worksystem.model.BusinessTripReport report = tripReportMapper.findById(id);
        if (report == null) {
            throw new BusinessException("출장보고서를 찾을 수 없습니다.");
        }
        return report;
    }

    private void storeTripReportFiles(Long reportId, List<MultipartFile> files) {
        if (files == null) {
            return;
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            if (file.getSize() > MAX_TRIP_RECEIPT_BYTES) {
                throw new BusinessException("출장보고 첨부는 10MB 이하 PNG 또는 PDF만 등록할 수 있습니다.");
            }
            ReceiptFileType fileType = detectReceiptFileType(file);
            validateReceiptContentType(file, fileType);
            Path root = tripReceiptRoot();
            String filename = "report-" + reportId + "-" + UUID.randomUUID() + "." + fileType.extension;
            Path target = root.resolve(filename).normalize();
            if (!target.startsWith(root)) {
                throw new BusinessException("첨부 저장 경로가 올바르지 않습니다.");
            }
            try {
                Files.createDirectories(root);
                try (InputStream inputStream = file.getInputStream()) {
                    Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new BusinessException("첨부 저장에 실패했습니다.");
            }
            com.portfolio.worksystem.util.AfterCommit.deleteUploadOnRollback(target, "출장보고 첨부");
            com.portfolio.worksystem.model.BusinessTripReportFile stored = new com.portfolio.worksystem.model.BusinessTripReportFile();
            stored.setReportId(reportId);
            stored.setStoredFilename(filename);
            stored.setOriginalFilename(originalFilename(file));
            stored.setContentType(fileType.contentType);
            stored.setFileSize(file.getSize());
            tripReportMapper.insertFile(stored);
        }
    }

    private void purgeTripReport(Long reportId) {
        Path root = tripReceiptRoot();
        for (com.portfolio.worksystem.model.BusinessTripReportFile f : tripReportMapper.findFiles(reportId)) {
            try {
                Files.deleteIfExists(root.resolve(f.getStoredFilename()).normalize());
            } catch (IOException e) {
                // 파일 삭제 실패는 무시(DB 정리는 계속)
            }
        }
        approvalService.deleteSteps(ApprovalDocumentType.TRIP_REPORT, reportId);
        tripReportMapper.deleteFiles(reportId);
        tripReportMapper.deleteExpenses(reportId);
        tripReportMapper.deleteReport(reportId);
    }

    /* ── 결재문서 열람·보관 — 실제 구현은 ApprovalDocumentService. 화면이 오래 쓰던 이름이라 여기서 넘겨준다. ── */

    public List<ApprovalStep> leaveApprovalSteps(Long requestId) {
        return approvalDocumentService.leaveApprovalSteps(requestId);
    }

    public List<ApprovalStep> tripApprovalSteps(Long tripId) {
        return approvalDocumentService.tripApprovalSteps(tripId);
    }

    public LeaveRequest leaveDocument(CustomUserDetails user, Long id) {
        return approvalDocumentService.leaveDocument(user, id);
    }

    public BusinessTrip tripDocument(CustomUserDetails user, Long id) {
        return approvalDocumentService.tripDocument(user, id);
    }

    public com.portfolio.worksystem.model.BusinessTripReport tripReportDocument(CustomUserDetails user, Long id) {
        return approvalDocumentService.tripReportDocument(user, id);
    }

    public FlexTimeRequest flexTimeDocument(CustomUserDetails user, Long id) {
        return approvalDocumentService.flexTimeDocument(user, id);
    }

    public AttendanceCorrection attendanceCorrectionDocument(CustomUserDetails user, Long id) {
        return approvalDocumentService.attendanceCorrectionDocument(user, id);
    }

    public ApprovalDocumentSnapshot approvalDocumentSnapshot(CustomUserDetails currentUser,
                                                             ApprovalDocumentType documentType,
                                                             Long documentId) {
        return approvalDocumentService.approvalDocumentSnapshot(currentUser, documentType, documentId);
    }

    public void postApproveDocument(CustomUserDetails currentUser, ApprovalDocumentType documentType, Long documentId) {
        approvalDocumentService.postApproveDocument(currentUser, documentType, documentId);
    }

    public ApprovalDocumentService.StoredApprovalDocument loadApprovalDocumentSnapshotFile(
            CustomUserDetails currentUser, ApprovalDocumentType documentType, Long documentId) {
        return approvalDocumentService.loadApprovalDocumentSnapshotFile(currentUser, documentType, documentId);
    }

    public int regenerateSnapshotFiles(String teamName) {
        return approvalDocumentService.regenerateSnapshotFiles(teamName);
    }

    /** 결재 확정 시 그때 모습대로 보관문서를 굳힌다. */
    private void storeFinalDocumentSnapshot(ApprovalDocumentType documentType, Long documentId, Long generatedBy) {
        approvalDocumentService.storeFinalDocumentSnapshot(documentType, documentId, generatedBy);
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> approvalPreview(CustomUserDetails currentUser, ApprovalDocumentType documentType, LocalDate startDate, LocalDate endDate) {
        LocalDate fallback = LocalDate.now(SERVICE_ZONE);
        return approvalService.previewRoute(currentUser, documentType, startDate == null ? fallback : startDate, endDate == null ? fallback : endDate);
    }

    @Transactional(readOnly = true)
    public String approvalRouteAlert(CustomUserDetails currentUser, ApprovalDocumentType documentType, LocalDate startDate, LocalDate endDate) {
        LocalDate fallback = LocalDate.now(SERVICE_ZONE);
        return approvalService.routeAlert(currentUser, documentType, startDate == null ? fallback : startDate, endDate == null ? fallback : endDate);
    }

    @Transactional(readOnly = true)
    public String approvalRouteWarning(CustomUserDetails currentUser, ApprovalDocumentType documentType, LocalDate startDate, LocalDate endDate) {
        LocalDate fallback = LocalDate.now(SERVICE_ZONE);
        LocalDate start = startDate == null ? fallback : startDate;
        LocalDate end = endDate == null ? start : endDate;
        if (end.isBefore(start)) {
            end = start;
        }
        final LocalDate checkStart = start;
        final LocalDate checkEnd = end;
        List<String> warnings = new ArrayList<>();

        // Date validation for new submissions (weekend / company holiday / personal overlap).
        if (documentType == ApprovalDocumentType.LEAVE
                || documentType == ApprovalDocumentType.TRIP
                || documentType == ApprovalDocumentType.FLEX_TIME) {
            // 결재경로 안내는 어떤 휴가 종류인지 모른다 — 겹침은 종류 무관 일반 판정으로 본다.
            warnings.addAll(dateValidationWarnings(currentUser, documentType, checkStart, checkEnd, null));
        }

        List<ApprovalStep> steps = approvalPreview(currentUser, documentType, checkStart, checkEnd);
        if (!steps.isEmpty()) {
            List<LeaveRequest> leaves = new ArrayList<>();
            for (int year = checkStart.getYear(); year <= checkEnd.getYear(); year++) {
                leaves.addAll(hrMapper.findLeaveRequests(null, null, year).stream()
                        .filter(request -> request.getStatus() == LeaveStatus.APPROVED
                                || request.getStatus() == LeaveStatus.CANCEL_PENDING)
                        .toList());
            }
            List<BusinessTrip> trips = hrMapper.findApprovedBusinessTrips(null, null, checkStart, checkEnd);
            for (ApprovalStep step : steps) {
                UserAccount approver = userMapper.findById(step.getApproverUserId());
                String approverName = approver == null ? blankToDash(step.getApproverName()) : approver.getName();
                boolean onLeave = leaves.stream().anyMatch(request -> step.getApproverUserId().equals(request.getUserId())
                        && datesOverlap(request.getStartDate(), request.getEndDate(), checkStart, checkEnd));
                boolean onTrip = trips.stream().anyMatch(trip -> step.getApproverUserId().equals(trip.getUserId())
                        && datesOverlap(trip.getStartDate(), trip.getEndDate(), checkStart, checkEnd));
                if (onLeave || onTrip) {
                    warnings.add(approverName + " 결재자가 신청 기간 중 " + (onLeave ? "휴가" : "출장") + " 예정입니다.");
                }
                if (step.getDelegateUserId() != null && StringUtils.hasText(step.getDelegateName())) {
                    warnings.add(approverName + " 결재자에게 대리승인자 '" + step.getDelegateName() + "'이 설정되어 있습니다.");
                }
            }
        }
        if (warnings.isEmpty()) {
            return null;
        }
        return String.join("\n", warnings);
    }

    /**
     * @param leaveTypeCode 휴가 종류 코드. 주면 겹침 판정이 신청 검증({@link #conflictingLeave})과
     *                      같아져 화면 경고와 실제 차단이 어긋나지 않는다. 모르면 null —
     *                      종류 무관 일반 겹침으로 본다.
     */
    private List<String> dateValidationWarnings(CustomUserDetails currentUser, ApprovalDocumentType type,
                                                LocalDate start, LocalDate end, String leaveTypeCode) {
        List<String> warnings = new ArrayList<>();
        Long myId = currentUser.getUserId();

        // 1) 휴무 요일(주말 등, 요일별 근무시간표 기준) 포함 여부
        Map<Integer, AttendanceWeekDay> week = weekSchedule();
        LocalDate cursor = start;
        boolean hasOffDay = false;
        while (!cursor.isAfter(end)) {
            if (weekDayOf(week, cursor).isOff()) {
                hasOffDay = true;
                break;
            }
            cursor = cursor.plusDays(1);
        }
        if (hasOffDay) {
            warnings.add("신청 기간에 휴무 요일(주말 등)이 포함되어 있습니다.");
        }

        // 2) Company holidays in range
        List<CompanyHoliday> holidays = hrMapper.findCompanyHolidays(start, end);
        if (holidays != null && !holidays.isEmpty()) {
            String names = holidays.stream()
                    .map(h -> {
                        String name = StringUtils.hasText(h.getName()) ? h.getName() : "휴무일";
                        return name + "(" + h.getHolidayDate() + ")";
                    })
                    .collect(java.util.stream.Collectors.joining(", "));
            warnings.add("신청 기간에 회사 휴무일이 포함되어 있습니다: " + names);
        }

        // 3) 본인 휴가와 겹치는지 — 종류를 알면 신청 검증과 똑같이(반차 조합 허용) 본다.
        if (leaveTypeCode != null) {
            LeaveRequest conflict = conflictingLeave(myId, leaveTypeCode, start, end, null);
            if (conflict != null) {
                warnings.add(overlapMessage(conflict));
            }
        } else {
            boolean leaveOverlap = false;
            for (int year = start.getYear(); year <= end.getYear() && !leaveOverlap; year++) {
                List<LeaveRequest> myLeaves = hrMapper.findLeaveRequests(myId, null, year);
                if (myLeaves == null) continue;
                leaveOverlap = myLeaves.stream()
                        .filter(r -> holdsLeaveDays(r.getStatus()))
                        .anyMatch(r -> datesOverlap(r.getStartDate(), r.getEndDate(), start, end));
            }
            if (leaveOverlap) {
                warnings.add("같은 기간에 이미 본인 휴가가 신청/승인되어 있습니다.");
            }
        }

        // 4) Personal overlap with existing trips (PENDING/APPROVED)
        boolean tripOverlap = false;
        for (int year = start.getYear(); year <= end.getYear() && !tripOverlap; year++) {
            List<BusinessTrip> myTrips = hrMapper.findBusinessTrips(myId, null, year);
            if (myTrips == null) continue;
            tripOverlap = myTrips.stream()
                    .filter(t -> t.getStatus() == BusinessTripStatus.PENDING || t.getStatus() == BusinessTripStatus.APPROVED)
                    .anyMatch(t -> datesOverlap(t.getStartDate(), t.getEndDate(), start, end));
        }
        if (tripOverlap) {
            warnings.add("같은 기간에 이미 본인 출장이 신청/승인되어 있습니다.");
        }

        return warnings;
    }

    private void requireApprovalRoute(CustomUserDetails currentUser, ApprovalDocumentType documentType, LocalDate startDate, LocalDate endDate) {
        String alert = approvalRouteAlert(currentUser, documentType, startDate, endDate);
        if (alert != null) {
            throw new BusinessException(alert);
        }
    }

    @Transactional(readOnly = true)
    public List<ApprovalInboxItem> approvalInbox(CustomUserDetails currentUser) {
        return approvalService.inbox(currentUser);
    }

    @Transactional(readOnly = true)
    public List<BusinessTripExpense> businessTripExpenses(CustomUserDetails currentUser, Long tripId) {
        requireTripVisible(currentUser, getBusinessTrip(tripId));
        return hrMapper.findBusinessTripExpenses(tripId);
    }

    @Transactional(readOnly = true)
    public StoredBusinessTripReceipt loadBusinessTripReceipt(CustomUserDetails currentUser, Long receiptId) {
        BusinessTripReceipt receipt = hrMapper.findBusinessTripReceipt(receiptId);
        if (receipt == null) {
            throw new BusinessException("영수증 파일을 찾을 수 없습니다.");
        }
        BusinessTrip trip = getBusinessTrip(receipt.getTripId());
        requireTripVisible(currentUser, trip);
        Path root = tripReceiptRoot();
        Path file = root.resolve(receipt.getStoredFilename()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BusinessException("영수증 파일을 찾을 수 없습니다.");
        }
        try {
            return new StoredBusinessTripReceipt(
                    new UrlResource(file.toUri()),
                    MediaType.parseMediaType(receipt.getContentType()),
                    receipt.getOriginalFilename()
            );
        } catch (IOException e) {
            throw new BusinessException("영수증 파일을 읽을 수 없습니다.");
        }
    }

    private LeaveRequest getLeaveRequest(Long requestId) {
        LeaveRequest request = hrMapper.findLeaveRequest(requestId);
        if (request == null) {
            throw new BusinessException("연차 신청을 찾을 수 없습니다.");
        }
        return request;
    }

    private BusinessTrip getBusinessTrip(Long tripId) {
        BusinessTrip trip = hrMapper.findBusinessTrip(tripId);
        if (trip == null) {
            throw new BusinessException("출장 신청을 찾을 수 없습니다.");
        }
        return trip;
    }

    private void requireLeaveApprover(CustomUserDetails currentUser, LeaveRequest request) {
        if (canManageHr(currentUser)) {
            return;
        }
        if (currentUser != null && currentUser.isTeamLeader()
                && request.getTeamId() != null
                && request.getTeamId().equals(currentUser.getTeamId())
                && !request.getUserId().equals(currentUser.getUserId())) {
            return;
        }
        throw new BusinessException("연차 승인 권한이 없습니다.");
    }

    private void requireTripApprover(CustomUserDetails currentUser, BusinessTrip trip) {
        if (canManageHr(currentUser)) {
            return;
        }
        if (currentUser != null && currentUser.isTeamLeader()
                && trip.getTeamId() != null
                && trip.getTeamId().equals(currentUser.getTeamId())
                && !trip.getUserId().equals(currentUser.getUserId())) {
            return;
        }
        throw new BusinessException("출장 승인 권한이 없습니다.");
    }

    private void requireHrManager(CustomUserDetails currentUser) {
        if (!canManageHr(currentUser)) {
            throw new BusinessException("HR 관리자 권한이 필요합니다.");
        }
    }

    private LeaveBalance ensureBalance(Long userId, Integer year) {
        UserAccount user = userMapper.findById(userId);
        BigDecimal entitlement = annualLeaveEntitlement(user == null ? null : user.getHireDate(), year,
                loaService.excludedLoaDatesByUser(year).get(userId), user == null ? null : user.getResignedOn());
        LeaveBalance balance = hrMapper.findLeaveBalance(userId, year);
        if (balance != null) {
            balance.setTotalDays(entitlement);
            balance.setNote("입사일 기준 자동 산정");
            hrMapper.upsertLeaveBalance(balance);
            return balance;
        }
        balance = new LeaveBalance();
        balance.setUserId(userId);
        balance.setBalanceYear(year);
        balance.setTotalDays(entitlement);
        balance.setUsedDays(BigDecimal.ZERO);
        balance.setAdjustedDays(BigDecimal.ZERO);
        balance.setNote("입사일 기준 자동 산정");
        hrMapper.insertLeaveBalance(balance);
        return balance;
    }

    private List<BusinessTripExpense> normalizeTripExpenses(List<BusinessTripExpenseForm> forms) {
        if (forms == null) {
            return List.of();
        }
        List<BusinessTripExpense> expenses = new ArrayList<>();
        for (BusinessTripExpenseForm form : forms) {
            if (form == null) {
                continue;
            }
            boolean empty = form.getExpenseType() == null
                    && !StringUtils.hasText(form.getDescription())
                    && form.getAmount() == null
                    && !StringUtils.hasText(form.getNote());
            if (empty) {
                continue;
            }
            if (form.getExpenseType() == null) {
                throw new BusinessException("출장 비용 항목을 선택하세요.");
            }
            if (form.getAmount() == null || form.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("출장 비용 금액은 0보다 크게 입력하세요.");
            }
            BusinessTripExpense expense = new BusinessTripExpense();
            expense.setExpenseType(form.getExpenseType());
            expense.setDescription(trimToNull(form.getDescription()));
            expense.setAmount(form.getAmount());
            expense.setNote(trimToNull(form.getNote()));
            expenses.add(expense);
        }
        return expenses;
    }

    private Map<BusinessTripExpenseType, BigDecimal> tripExpenseTotals(List<BusinessTripExpense> expenses) {
        Map<BusinessTripExpenseType, BigDecimal> totals = new EnumMap<>(BusinessTripExpenseType.class);
        for (BusinessTripExpense expense : expenses) {
            totals.merge(expense.getExpenseType(), zero(expense.getAmount()), BigDecimal::add);
        }
        return totals;
    }

    private void storeTripReceiptIfPresent(CustomUserDetails currentUser, Long tripId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        if (file.getSize() > MAX_TRIP_RECEIPT_BYTES) {
            throw new BusinessException("출장 영수증은 10MB 이하 PNG 또는 PDF만 등록할 수 있습니다.");
        }
        ReceiptFileType fileType = detectReceiptFileType(file);
        validateReceiptContentType(file, fileType);
        Path root = tripReceiptRoot();
        String filename = tripId + "-" + UUID.randomUUID() + "." + fileType.extension;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("영수증 저장 경로가 올바르지 않습니다.");
        }
        try {
            Files.createDirectories(root);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException("영수증 저장에 실패했습니다.");
        }
        com.portfolio.worksystem.util.AfterCommit.deleteUploadOnRollback(target, "출장 영수증");
        BusinessTripReceipt receipt = new BusinessTripReceipt();
        receipt.setTripId(tripId);
        receipt.setOriginalFilename(originalFilename(file));
        receipt.setStoredFilename(filename);
        receipt.setContentType(fileType.contentType);
        receipt.setFileSize(file.getSize());
        receipt.setUploadedBy(currentUser.getUserId());
        hrMapper.insertBusinessTripReceipt(receipt);
    }

    private ReceiptFileType detectReceiptFileType(MultipartFile file) {
        byte[] header;
        try (InputStream inputStream = file.getInputStream()) {
            header = inputStream.readNBytes(8);
        } catch (IOException e) {
            throw new BusinessException("영수증 파일을 읽을 수 없습니다.");
        }
        if (header.length >= 8
                && (header[0] & 0xff) == 0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47
                && header[4] == 0x0d && header[5] == 0x0a && header[6] == 0x1a && header[7] == 0x0a) {
            return ReceiptFileType.PNG;
        }
        if (header.length >= 4 && header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46) {
            return ReceiptFileType.PDF;
        }
        throw new BusinessException("출장 영수증은 PNG 또는 PDF 파일만 등록할 수 있습니다.");
    }

    private void validateReceiptContentType(MultipartFile file, ReceiptFileType fileType) {
        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType)) {
            return;
        }
        if (!fileType.contentType.equalsIgnoreCase(contentType)) {
            throw new BusinessException("영수증 파일 형식과 내용이 일치하지 않습니다.");
        }
    }

    private String originalFilename(MultipartFile file) {
        String cleaned = StringUtils.cleanPath(file.getOriginalFilename() == null ? "receipt." + detectReceiptFileType(file).extension : file.getOriginalFilename());
        return cleaned.length() > 255 ? cleaned.substring(cleaned.length() - 255) : cleaned;
    }

    private void requireTripVisible(CustomUserDetails currentUser, BusinessTrip trip) {
        if (trip == null) {
            throw new BusinessException("출장 신청을 찾을 수 없습니다.");
        }
        if (canManageHr(currentUser) || trip.getUserId().equals(currentUser.getUserId())) {
            return;
        }
        if (approvalService.canViewDocument(currentUser, ApprovalDocumentType.TRIP, trip.getId())) {
            return;
        }
        ApprovalStep pendingStep = approvalService.pendingStep(ApprovalDocumentType.TRIP, trip.getId());
        if (pendingStep != null
                && (currentUser.getUserId().equals(pendingStep.getApproverUserId())
                || currentUser.getUserId().equals(pendingStep.getDelegateUserId()))) {
            return;
        }
        throw new BusinessException("출장 신청 조회 권한이 없습니다.");
    }

    private BigDecimal annualLeaveEntitlement(LocalDate hireDate, int targetYear) {
        return annualLeaveEntitlement(hireDate, targetYear, null);
    }

    /**
     * @param excludedLoaDates [B7] 그 해 <b>연차에서 빼야 할</b> 휴직 날짜들(없으면 null).
     *                         육아휴직·업무상 재해는 법이 출근으로 간주하므로 여기 들어오지 않는다.
     */
    private BigDecimal annualLeaveEntitlement(LocalDate hireDate, int targetYear,
                                              Set<LocalDate> excludedLoaDates) {
        return annualLeaveEntitlement(hireDate, targetYear, excludedLoaDates, null);
    }

    /**
     * @param resignedOn [B8] 퇴사일(없으면 null). 퇴사한 해는 <b>재직한 기간까지만</b> 부여한다.
     */
    private BigDecimal annualLeaveEntitlement(LocalDate hireDate, int targetYear,
                                              Set<LocalDate> excludedLoaDates, LocalDate resignedOn) {
        if (hireDate == null) {
            return DEFAULT_ANNUAL_DAYS;
        }
        BigDecimal base = entitlementAsOf(hireDate, entitlementReferenceDate(hireDate, targetYear)); // [D3]
        return prorateForLeaveOfAbsence(base, targetYear,
                nonWorkingDates(targetYear, excludedLoaDates, resignedOn));
    }

    /**
     * [B8] 그 해 <b>부여에서 빼야 할</b> 날들 — 휴직분 + 퇴사 이후.
     *
     * <p>퇴사한 해는 연말까지 다닌 것으로 계산하면 안 된다. 6월에 그만둔 사람에게 연말 기준
     * 부여일수를 그대로 주면, 쓰지도 못할 연차가 미사용분으로 남아 <b>수당이 과다 산정</b>된다.
     * 퇴사일 다음날부터 연말까지를 빼서 재직 기간에 비례시킨다.
     */
    private Set<LocalDate> nonWorkingDates(int targetYear, Set<LocalDate> excludedLoaDates, LocalDate resignedOn) {
        if (resignedOn == null || resignedOn.getYear() > targetYear) {
            return excludedLoaDates;
        }
        Set<LocalDate> dates = excludedLoaDates == null
                ? new java.util.HashSet<>() : new java.util.HashSet<>(excludedLoaDates);
        LocalDate yearEnd = LocalDate.of(targetYear, 12, 31);
        LocalDate from = resignedOn.getYear() < targetYear
                ? LocalDate.of(targetYear, 1, 1)   // 작년에 이미 퇴사 — 그 해는 통째로 재직 아님
                : resignedOn.plusDays(1);
        for (LocalDate d = from; !d.isAfter(yearEnd); d = d.plusDays(1)) {
            dates.add(d);
        }
        return dates;
    }

    /**
     * [B7] 휴직 기간만큼 연차를 <b>비례해서</b> 깎는다.
     *
     * <p>근로기준법 제60조의 연차는 그 해 소정근로일을 다녔다는 전제 위에 있다. 1년 중 절반을
     * 휴직했으면 그 해 연차도 절반이 맞다. 예전에는 이 반영이 아예 없어서 1년 내내 쉰 사람도
     * 근속만 쌓여 연차가 그대로 나왔고, 그게 연차수당 정산까지 그대로 갔다.
     *
     * <p>기준은 <b>달력일이 아니라 소정근로일</b>이다 — 휴무 요일과 회사 휴무일을 뺀 날.
     * 달력일로 재면 주말이 많이 낀 휴직이 더 많이 깎인다.
     *
     * <p>결과는 <b>0.5일 단위로 반올림</b>한다. 연차는 반차까지만 쓸 수 있어 0.1일 단위는
     * 화면에서도 원장에서도 쓸 곳이 없다.
     */
    private BigDecimal prorateForLeaveOfAbsence(BigDecimal base, int targetYear, Set<LocalDate> excludedLoaDates) {
        if (excludedLoaDates == null || excludedLoaDates.isEmpty() || base.signum() <= 0) {
            return base;
        }
        Set<LocalDate> workdays = workdaysOfYear(targetYear);
        if (workdays.isEmpty()) {
            return base;
        }
        long excluded = excludedLoaDates.stream().filter(workdays::contains).count();
        if (excluded == 0) {
            return base;
        }
        if (excluded >= workdays.size()) {
            return BigDecimal.ZERO;   // 그 해 내내 휴직 — 발생분이 없다.
        }
        BigDecimal ratio = BigDecimal.valueOf(workdays.size() - excluded)
                .divide(BigDecimal.valueOf(workdays.size()), 6, java.math.RoundingMode.HALF_UP);
        // 0.5일 단위 반올림 = (값 × 2) 를 정수로 반올림한 뒤 다시 반으로.
        BigDecimal halves = base.multiply(ratio).multiply(BigDecimal.valueOf(2))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        return halves.divide(BigDecimal.valueOf(2), 1, java.math.RoundingMode.UNNECESSARY);
    }

    /** 그 해 소정근로일 — 휴무 요일과 회사 휴무일을 뺀 날. 한 해에 한 번만 계산해 쓴다. */
    private Set<LocalDate> workdaysOfYear(int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        Set<LocalDate> holidays = hrMapper.findCompanyHolidays(from, to).stream()
                .map(CompanyHoliday::getHolidayDate)
                .collect(java.util.stream.Collectors.toSet());
        Map<Integer, AttendanceWeekDay> week = weekSchedule();
        Set<LocalDate> workdays = new java.util.LinkedHashSet<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (holidays.contains(d) || weekDayOf(week, d).isOff()) {
                continue;
            }
            workdays.add(d);
        }
        return workdays;
    }

    /**
     * <b>어느 시점을 기준으로</b> 근속을 재느냐만 다른 부여 산식.
     *
     * <p>기준일을 밖에서 받는 이유는, 같은 연도라도 <b>연말 기준</b>과 <b>신청일 기준</b>의
     * 부여일수가 다를 수 있어서다. 입사일 기준(HIRE_DATE)에서는 입사기념일이 지나야 가산이
     * 붙으므로, 예를 들어 2014-06-01 입사자의 2027년 부여는 연말 기준 21일이지만 6/1 이전에는
     * 20일까지만 발생한 상태다. 화면은 연말 기준(그 해 총 부여)으로 계산하되, 이 차이를
     * 사람마다 안내해 주려면 두 값이 모두 필요하다.
     */
    private BigDecimal entitlementAsOf(LocalDate hireDate, LocalDate referenceDate) {
        if (hireDate.isAfter(referenceDate)) {
            return BigDecimal.ZERO;
        }
        Period period = Period.between(hireDate, referenceDate);
        if (period.getYears() < 1) {
            // 근속 1년 미만 월차 — 법정상 매월 개근 시 1일씩 별도로 발생한다.
            // 가산휴가 상한(max_annual_days)은 이 구간에 적용하지 않는다.
            int months = period.getYears() * 12 + period.getMonths();
            return BigDecimal.valueOf(Math.max(0, Math.min(11, months)));
        }
        int extraDays = Math.max(0, (period.getYears() - 1) / 2);
        return applyEntitlementCap(DEFAULT_ANNUAL_DAYS.add(BigDecimal.valueOf(extraDays)));
    }

    /**
     * [D3] 가산 포함 총 부여일수 상한(V81) 적용. 정책에 값이 없으면 상한 없음.
     *
     * <p>근로기준법 제60조 4항은 25일을 한도로 두는데, 이 코드의 산식(15 + (근속-1)/2)에는
     * 상한이 없어 근속 23년부터 25일을 넘었다. 법정 최소를 웃도는 부여 자체는 회사가 정할
     * 일이라 기본값은 두지 않고, 관리자가 넣은 값만 적용한다.
     */
    private BigDecimal applyEntitlementCap(BigDecimal entitlement) {
        Integer cap = leaveAccrualPolicy().getMaxAnnualDays();
        return cap == null ? entitlement : entitlement.min(BigDecimal.valueOf(cap));
    }

    /* ── [D3] 연차 부여 기준 정책 (V77) ── */

    @Transactional(readOnly = true)
    public com.portfolio.worksystem.model.LeaveAccrualPolicy leaveAccrualPolicy() {
        com.portfolio.worksystem.model.LeaveAccrualPolicy policy = hrMapper.findLeaveAccrualPolicy();
        if (policy == null) {
            policy = new com.portfolio.worksystem.model.LeaveAccrualPolicy();
            policy.setBasis("HIRE_DATE");
            policy.setEffectiveFromYear(2000);
        }
        return policy;
    }

    /**
     * [D3] 부여 산정 기준일 — FISCAL_YEAR 정책이 해당 연도에 적용되고 1/1 기준 근속 1년 이상이면
     * 연초(1/1)로 확정(연중 불변 — 입사기념일이 지나도 잔여가 늘지 않음).
     * 그 외(HIRE_DATE 기준, 정책 적용 전 연도, 1년 미만 월차 발생 구간)는 기존 방식
     * (당해=오늘, 과거·미래=12/31) — 1년 미만 월차는 법정상 매월 발생이라 기준과 무관하게 유지.
     */
    private LocalDate entitlementReferenceDate(LocalDate hireDate, int targetYear) {
        com.portfolio.worksystem.model.LeaveAccrualPolicy policy = leaveAccrualPolicy();
        if (policy.isFiscalYear() && policy.appliesTo(targetYear)) {
            LocalDate jan1 = LocalDate.of(targetYear, 1, 1);
            if (!hireDate.isAfter(jan1.minusYears(1))) {
                return jan1;
            }
        }
        return referenceDate(targetYear);
    }

    /**
     * [D3] 정책 저장 + 저장된 원장 재계산.
     * scope: THIS_YEAR(올해부터) | FROM_YEAR(fromYear부터) | RETRO_ALL(전체 소급 — 과거 연도 원장도 변경).
     *
     * @param maxAnnualDays 가산 포함 총 부여일수 상한(V81). null 이면 상한 없음.
     */
    @Transactional
    public String updateLeaveAccrualPolicy(CustomUserDetails currentUser, String basis, String scope,
                                           Integer fromYear, Integer maxAnnualDays) {
        requireHrManager(currentUser);
        if (!"HIRE_DATE".equals(basis) && !"FISCAL_YEAR".equals(basis)) {
            throw new BusinessException("부여 기준은 입사일 기준/회계연도 기준 중 하나여야 합니다.");
        }
        // 상한이 기본 부여일수보다 작으면 상한 때문에 기본 부여가 깎인다. 그건 상한이 아니라 감축이다.
        int baseDays = DEFAULT_ANNUAL_DAYS.intValue();
        if (maxAnnualDays != null && (maxAnnualDays < baseDays || maxAnnualDays > MAX_ENTITLEMENT_CAP)) {
            throw new BusinessException("부여 상한은 기본 부여일수(" + baseDays + "일) 이상 "
                    + MAX_ENTITLEMENT_CAP + "일 이하로 입력하세요. 상한을 두지 않으려면 비워 두세요.");
        }
        int thisYear = currentYear();
        int effectiveYear;
        boolean retro = false;
        switch (scope == null ? "" : scope) {
            case "THIS_YEAR" -> effectiveYear = thisYear;
            case "FROM_YEAR" -> {
                if (fromYear == null || fromYear < 2000 || fromYear > thisYear + 1) {
                    throw new BusinessException("적용 시작 연도를 확인하세요(2000~" + (thisYear + 1) + ").");
                }
                effectiveYear = fromYear;
            }
            case "RETRO_ALL" -> {
                effectiveYear = 2000;
                retro = true;
            }
            default -> throw new BusinessException("적용 범위를 선택하세요.");
        }
        com.portfolio.worksystem.model.LeaveAccrualPolicy policy = new com.portfolio.worksystem.model.LeaveAccrualPolicy();
        policy.setBasis(basis);
        policy.setEffectiveFromYear(effectiveYear);
        policy.setMaxAnnualDays(maxAnnualDays);
        policy.setUpdatedBy(currentUser.getUserId());
        hrMapper.updateLeaveAccrualPolicy(policy);
        // 저장된 원장 재계산 — 적용 연도 이후(소급이면 전체)의 부여(total_days)를 새 기준으로 갱신.
        // 사용/조정분은 건드리지 않으므로 잔여만 부여 변화분만큼 달라진다.
        int recalced = 0;
        // [B7] 휴직 제외일은 <연도별>로 한 번만 조회해 나눠 쓴다 — 사람마다 부르면 쿼리가 인원수만큼 난다.
        Map<Integer, Map<Long, java.util.Set<LocalDate>>> loaByYear = new HashMap<>();
        for (LeaveBalance balance : hrMapper.findLeaveBalancesFromYear(retro ? 0 : effectiveYear)) {
            UserAccount balanceUser = userMapper.findById(balance.getUserId());
            BigDecimal entitlement = annualLeaveEntitlement(
                    balanceUser == null ? null : balanceUser.getHireDate(), balance.getBalanceYear(),
                    loaByYear.computeIfAbsent(balance.getBalanceYear(), loaService::excludedLoaDatesByUser)
                            .get(balance.getUserId()),
                    balanceUser == null ? null : balanceUser.getResignedOn());
            if (balance.getTotalDays() == null || balance.getTotalDays().compareTo(entitlement) != 0) {
                hrMapper.updateLeaveBalanceTotal(balance.getId(), entitlement);
                recalced++;
            }
        }
        String basisLabel = "FISCAL_YEAR".equals(basis) ? "회계연도 기준" : "입사일 기준";
        String scopeLabel = retro ? "전체 소급" : effectiveYear + "년부터";
        String capLabel = maxAnnualDays == null ? "부여 상한 없음" : "부여 상한 " + maxAnnualDays + "일";
        return "연차 부여 기준을 '" + basisLabel + "'(" + scopeLabel + ", " + capLabel + ")으로 저장했습니다. 원장 "
                + recalced + "건의 부여일수를 재계산했습니다.";
    }

    private LocalDate referenceDate(int targetYear) {
        return targetYear == currentYear()
                ? LocalDate.now(SERVICE_ZONE)
                : LocalDate.of(targetYear, Month.DECEMBER, 31);
    }

    private String formatDays(BigDecimal value) {
        return zero(value).stripTrailingZeros().toPlainString();
    }

    private BigDecimal calculateLeaveDays(String type, LocalDate startDate, LocalDate endDate) {
        Set<LocalDate> holidays = new HashSet<>(hrMapper.findCompanyHolidays(startDate, endDate).stream()
                .map(CompanyHoliday::getHolidayDate)
                .toList());
        Map<Integer, AttendanceWeekDay> week = weekSchedule();
        Map<LocalDate, AttendanceDayOverride> overrides = new HashMap<>();
        hrMapper.findAttendanceOverrides(startDate, endDate)
                .forEach(override -> overrides.put(override.getWorkDate(), override));
        com.portfolio.worksystem.model.LeaveTypeDef typeDef = leaveTypeDef(type); // [C2]
        if (typeDef.isHalfDay()) {
            if (!startDate.equals(endDate)) {
                throw new BusinessException("반차는 하루만 신청할 수 있습니다.");
            }
            // 반일(HALF) 근무 요일의 반차는 그날 근무 전체(0.5일)와 같으므로 가중치를 넘지 않게 한다.
            return leaveDayWeight(startDate, holidays, week, overrides).min(typeDef.getDayWeight());
        }
        BigDecimal days = BigDecimal.ZERO;
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            days = days.add(leaveDayWeight(cursor, holidays, week, overrides));
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    /** 휴무·공휴일 0, 반일 요일 또는 4시간 이하 일자예외 0.5, 전일 1. */
    private BigDecimal leaveDayWeight(LocalDate date, Set<LocalDate> holidays, Map<Integer, AttendanceWeekDay> week,
                                     Map<LocalDate, AttendanceDayOverride> overrides) {
        if (holidays.contains(date)) {
            return BigDecimal.ZERO;
        }
        AttendanceDayOverride override = overrides.get(date);
        if (override != null) {
            return java.time.Duration.between(override.getStartTime(), override.getEndTime())
                    .compareTo(java.time.Duration.ofHours(HALF_DAY_HOURS)) <= 0
                    ? new BigDecimal("0.50") : BigDecimal.ONE;
        }
        AttendanceWeekDay day = weekDayOf(week, date);
        if (day.isOff()) {
            return BigDecimal.ZERO;
        }
        return day.isHalf() ? new BigDecimal("0.50") : BigDecimal.ONE;
    }

    private boolean datesOverlap(LocalDate leftStart, LocalDate leftEnd, LocalDate rightStart, LocalDate rightEnd) {
        return leftStart != null && leftEnd != null && rightStart != null && rightEnd != null
                && !leftEnd.isBefore(rightStart)
                && !leftStart.isAfter(rightEnd);
    }

    private HrScopeService.Visibility visibility(CustomUserDetails currentUser) {
        return hrScopeService.visibility(currentUser);
    }

    private Set<Long> memberScopeUserIds(CustomUserDetails currentUser) {
        return hrScopeService.memberScopeUserIds(currentUser);
    }

    /** [Q-3] 본부장(DIVISION 노드 리더) 여부. */
    public boolean isDivisionLeader(CustomUserDetails currentUser) {
        return hrScopeService.isDivisionLeader(currentUser);
    }

    /** [Q-3] 본부장이 보는 산하 팀 목록(자기 본부 + 자손 TEAM 노드). canManageWeeklyReport=true면 전체. */
    public List<Team> reportVisibleTeams(CustomUserDetails currentUser) {
        return hrScopeService.reportVisibleTeams(currentUser);
    }

    private List<UserAccount> visibleUsers(CustomUserDetails currentUser) {
        return hrScopeService.visibleUsers(currentUser);
    }

    private BusinessTrip approvedTripForDate(List<BusinessTrip> trips, Long userId, LocalDate date) {
        return trips.stream()
                .filter(trip -> trip.getUserId().equals(userId))
                .filter(trip -> !date.isBefore(trip.getStartDate()) && !date.isAfter(trip.getEndDate()))
                .findFirst()
                .orElse(null);
    }

    private String key(Long userId, LocalDate date) {
        return userId + ":" + date;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String blankToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Path tripReceiptRoot() {
        return Path.of(tripReceiptDir).toAbsolutePath().normalize();
    }

    private String normalizeScheduleColor(String color) {
        if (!StringUtils.hasText(color)) {
            return "#2563eb";
        }
        String value = color.trim();
        return value.matches("^#[0-9a-fA-F]{6}$") ? value : "#2563eb";
    }

    /** 그날 실제로 일하기로 되어 있는 정규 근무 구간(반차는 줄인 구간). */
    public record WorkWindow(LocalDateTime start, LocalDateTime end) {
    }

    /**
     * [OT] 그날의 정규 근무 구간 — 시차출근·요일별 근무시간·일자 예외를 반영하고, 반차면 그만큼 줄인다.
     * 종일 휴가·휴무일처럼 정규 근무가 없는 날은 null. 시간외 근무는 이 구간 밖의 시간만 인정한다.
     */
    @Transactional(readOnly = true)
    public WorkWindow regularWorkWindow(Long userId, LocalDate date) {
        if (hrMapper.findCompanyHoliday(date) != null
                || (weekDayOf(weekSchedule(), date).isOff() && hrMapper.findAttendanceOverride(date) == null)) {
            return null;
        }
        LeaveRequest leave = hrMapper.findApprovedLeaveForDate(userId, date);
        if (isFullDayLeave(leave)) {
            return null;
        }
        WorkSchedule schedule = workSchedule(userId, date);
        LocalTime start = judgmentStartTime(schedule, leave);
        LocalTime end = judgmentEndTime(schedule, leave);
        if (!end.isAfter(start)) {
            return null;
        }
        return new WorkWindow(LocalDateTime.of(date, start), LocalDateTime.of(date, end));
    }

    public record WorkSchedule(LocalTime startTime, LocalTime endTime, String note, boolean override) {
    }

    public record LeaveEntitlementSummary(String tenureText, String entitlementText, String entitlementDays) {
    }

    public record StoredBusinessTripReceipt(Resource resource, MediaType mediaType, String originalFilename) {
    }


    private enum ReceiptFileType {
        PNG("png", "image/png"),
        PDF("pdf", "application/pdf");

        private final String extension;
        private final String contentType;

        ReceiptFileType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }
    }
}
