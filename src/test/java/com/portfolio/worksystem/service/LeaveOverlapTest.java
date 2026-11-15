package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.dto.LeaveRequestForm;
import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.TripReportMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.ApprovalStep;
import com.portfolio.worksystem.model.LeaveRequest;
import com.portfolio.worksystem.model.LeaveStatus;
import com.portfolio.worksystem.model.LeaveTypeDef;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 같은 날 <b>중복 휴가</b>를 막는다.
 *
 * <p>예전에는 겹침을 미리보기에서 <b>경고만</b> 하고, 실제 신청({@code requestLeave})은
 * 아무 검사 없이 통과시켰다. 화면 경고를 무시하거나 API 를 직접 부르면 같은 날 휴가를
 * 몇 번이든 낼 수 있었고 그때마다 잔여가 <b>실제로 차감</b>됐다 —
 * 오전반차를 두 번 내면 1.0일이 깎이지만 실제로 쉬는 것은 반나절이다.
 * 손해가 직원 쪽으로 나므로 경고로 둘 일이 아니다.
 *
 * <p>다만 <b>오전반차 + 오후반차</b>는 하루를 나눠 쓰는 정상 조합이라 막으면 안 된다
 * (오전은 연차, 오후는 보건휴가처럼 종류가 다른 경우가 있다). 이 결이 이 테스트의 핵심이다.
 */
class LeaveOverlapTest {

    private static final long USER = 7L;
    private static final LocalDate MON = LocalDate.of(2025, 3, 3);
    private static final LocalDate WED = LocalDate.of(2025, 3, 5);
    private static final LocalDate FRI = LocalDate.of(2025, 3, 7);

    private final HrMapper hrMapper = mock(HrMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);

    private final HrService hrService = new HrService(
            hrMapper, mock(TripReportMapper.class), userMapper,
            approvalService, mock(CommunicationService.class),
            mock(PanelRefreshPublisher.class), mock(HrScopeService.class),
            mock(ApprovalDocumentService.class), mock(LeaveAllowanceService.class), mock(LoaService.class));

    @BeforeEach
    void setUp() {
        lenient().when(userMapper.findById(USER)).thenReturn(account());
        lenient().when(hrMapper.findWeekSchedule()).thenReturn(List.of());
        lenient().when(hrMapper.findCompanyHolidays(any(), any())).thenReturn(List.of());
        lenient().when(hrMapper.findLeaveRequests(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(hrMapper.findLeaveAccrualPolicy()).thenReturn(null);
        // 결재선은 이 테스트의 관심사가 아니다 — 한 단계 있는 것으로 둔다.
        lenient().when(approvalService.previewRoute(any(), any(), any(), any())).thenReturn(List.of(new ApprovalStep()));
        lenient().when(approvalService.createSteps(any(), any(), any(), any(), any()))
                .thenReturn(List.of(step()));
        givenLeaveType("ANNUAL", "연차", "1.00", false);
        givenLeaveType("AM_HALF", "오전반차", "0.50", false);
        givenLeaveType("PM_HALF", "오후반차", "0.50", false);
        givenLeaveType("HEALTH_AM", "보건반차(오전)", "0.50", false);
    }

    /* ── 막아야 하는 것 ───────────────────────────────── */

    /*
      핵심 회귀 — 이게 통과되면 잔여가 1.0일 깎이는데 실제로는 반나절만 쉰다.
    */
    @Test
    @DisplayName("같은 날 오전반차를 두 번 신청할 수 없다")
    void twoAmHalvesOnTheSameDayAreRejected() {
        givenExisting("AM_HALF", "오전반차", MON, MON, LeaveStatus.APPROVED);

        assertThatThrownBy(() -> request("AM_HALF", MON, MON))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 신청된 휴가");

        verify(hrMapper, never()).insertLeaveRequest(any());
        verify(hrMapper, never()).increaseUsedLeave(any(), any(), any());
    }

    @Test
    @DisplayName("종일 휴가가 있는 날에 반차를 겹쳐 신청할 수 없다")
    void halfDayOnTopOfAFullDayIsRejected() {
        givenExisting("ANNUAL", "연차", MON, MON, LeaveStatus.APPROVED);

        assertThatThrownBy(() -> request("AM_HALF", MON, MON))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("기간이 일부만 겹쳐도 막는다")
    void partialRangeOverlapIsRejected() {
        givenExisting("ANNUAL", "연차", MON, WED, LeaveStatus.APPROVED);

        assertThatThrownBy(() -> request("ANNUAL", WED, FRI))
                .isInstanceOf(BusinessException.class);
    }

    /*
      승인 대기(PENDING)도 이미 잔여를 차감한 상태다 — 신청 즉시 차감하기 때문이다.
    */
    @Test
    @DisplayName("아직 승인 전(대기)인 휴가와도 겹칠 수 없다")
    void pendingLeaveAlsoBlocks() {
        givenExisting("ANNUAL", "연차", MON, MON, LeaveStatus.PENDING);

        assertThatThrownBy(() -> request("ANNUAL", MON, MON))
                .isInstanceOf(BusinessException.class);
    }

    /*
      취소는 <승인돼야> 잔여가 돌아온다. 취소 결재가 진행 중인 동안 그 휴가는
      여전히 날짜와 일수를 붙들고 있으므로 겹침으로 봐야 한다.
    */
    @Test
    @DisplayName("취소 결재가 진행 중인 휴가와도 겹칠 수 없다")
    void cancelPendingStillBlocks() {
        givenExisting("ANNUAL", "연차", MON, MON, LeaveStatus.CANCEL_PENDING);

        assertThatThrownBy(() -> request("ANNUAL", MON, MON))
                .isInstanceOf(BusinessException.class);
    }

    /*
      고정 코드(AM_HALF/PM_HALF)로만 오전·오후를 판정한다. 그 밖의 반일 종류는
      어느 반나절인지 알 수 없으므로 막는다 — 덜 허용하는 쪽이 안전하다.
    */
    @Test
    @DisplayName("오전/오후를 알 수 없는 반일 종류끼리는 막는다")
    void unknownHalvesAreRejected() {
        givenExisting("HEALTH_AM", "보건반차(오전)", MON, MON, LeaveStatus.APPROVED);

        assertThatThrownBy(() -> request("AM_HALF", MON, MON))
                .isInstanceOf(BusinessException.class);
    }

    /* ── 통과해야 하는 것 ─────────────────────────────── */

    /*
      ★ 오전반차 + 오후반차는 하루를 나눠 쓰는 정상 조합이다.
        이걸 막으면 오전 연차·오후 보건휴가 같은 실제 운영을 못 하게 된다.
    */
    @Test
    @DisplayName("오전반차와 오후반차는 같은 날 함께 신청할 수 있다")
    void amAndPmHalvesShareTheDay() {
        givenExisting("AM_HALF", "오전반차", MON, MON, LeaveStatus.APPROVED);

        assertThatCode(() -> request("PM_HALF", MON, MON)).doesNotThrowAnyException();

        verify(hrMapper).insertLeaveRequest(any());
    }

    @Test
    @DisplayName("날짜가 겹치지 않으면 통과한다")
    void nonOverlappingDatesPass() {
        givenExisting("ANNUAL", "연차", MON, MON, LeaveStatus.APPROVED);

        assertThatCode(() -> request("ANNUAL", WED, FRI)).doesNotThrowAnyException();
    }

    /*
      반려·취소된 휴가는 잔여를 이미 돌려준 상태라 자리를 차지하지 않는다.
    */
    @Test
    @DisplayName("반려·취소된 휴가는 자리를 차지하지 않는다")
    void rejectedAndCanceledLeavesDoNotBlock() {
        givenExisting("ANNUAL", "연차", MON, MON, LeaveStatus.REJECTED);
        assertThatCode(() -> request("ANNUAL", MON, MON)).doesNotThrowAnyException();

        givenExisting("ANNUAL", "연차", MON, MON, LeaveStatus.CANCEL_APPROVED);
        assertThatCode(() -> request("ANNUAL", MON, MON)).doesNotThrowAnyException();
    }

    /* ── 미리보기 경고가 실제 차단과 같은 말을 하는지 ── */

    /*
      화면이 "겹친다" 고 경고하는데 서버는 통과시키거나, 그 반대이면 사용자는
      무엇을 믿어야 할지 알 수 없다. 둘이 같은 판정을 쓰는지 확인한다.
    */
    @Test
    @DisplayName("미리보기 경고와 실제 차단이 같은 판정을 쓴다")
    void previewWarningMatchesEnforcement() {
        givenExisting("AM_HALF", "오전반차", MON, MON, LeaveStatus.APPROVED);
        when(hrMapper.findBusinessTrips(any(), any(), anyInt())).thenReturn(List.of());

        HrService.LeaveDaysPreview blocked = hrService.leaveDaysPreview(user(), "AM_HALF", MON, MON);
        assertThat(blocked.warnings()).anyMatch(w -> w.contains("이미 신청된 휴가"));

        HrService.LeaveDaysPreview allowed = hrService.leaveDaysPreview(user(), "PM_HALF", MON, MON);
        assertThat(allowed.warnings()).noneMatch(w -> w.contains("이미 신청된 휴가"));
    }

    /* ── 도우미 ──────────────────────────────────────── */

    private void request(String type, LocalDate from, LocalDate to) {
        hrService.requestLeave(user(), new LeaveRequestForm(type, from, to, "사유"));
    }

    private void givenExisting(String type, String label, LocalDate from, LocalDate to, LeaveStatus status) {
        LeaveRequest existing = new LeaveRequest();
        existing.setId(100L);
        existing.setUserId(USER);
        existing.setType(type);
        existing.setTypeLabel(label);
        existing.setStartDate(from);
        existing.setEndDate(to);
        existing.setStatus(status);
        when(hrMapper.findLeaveRequests(any(), any(), anyInt())).thenReturn(List.of(existing));
    }

    private void givenLeaveType(String code, String label, String dayWeight, boolean deductAnnual) {
        LeaveTypeDef def = new LeaveTypeDef();
        def.setCode(code);
        def.setLabel(label);
        def.setDayWeight(new BigDecimal(dayWeight));
        def.setDeductAnnual(deductAnnual);
        def.setActive(true);
        lenient().when(hrMapper.findLeaveTypeDef(code)).thenReturn(def);
    }

    private ApprovalStep step() {
        ApprovalStep step = new ApprovalStep();
        step.setStepOrder(1);
        step.setApproverUserId(99L);
        return step;
    }

    private CustomUserDetails user() {
        return new CustomUserDetails(account());
    }

    private UserAccount account() {
        UserAccount user = new UserAccount();
        user.setId(USER);
        user.setUsername("kim");
        user.setName("김직원");
        user.setRoles(List.of("USER"));
        return user;
    }
}
