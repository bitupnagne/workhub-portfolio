package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.dto.LeaveRequestForm;
import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.TripReportMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.LeaveBalance;
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
 * 내년·내후년 날짜로 휴가를 올릴 때 <b>그 해 기준</b>으로 안내하고 계산한다.
 *
 * <p>연차는 연도별로 부여되고 연도별 원장에서 차감된다. 그런데 신청 폼 상단의 잔여·발생
 * 카드는 <b>항상 올해</b>였다. 날짜만 내년으로 바꾸면 아래 미리보기는 내년 기준으로 도는데
 * 위 카드는 올해에 머물러, 한 화면의 두 숫자가 서로 다른 해를 가리켰다 —
 * "잔여 3일"이라고 크게 떠 있는데 "신청 후 잔여 14일"이 나온다.
 *
 * <p>부여 방식은 <b>입사일 기준</b>이다(운영 확정). 그래서 같은 해라도 입사기념일 전후로
 * 총 부여가 달라질 수 있다. 화면은 <b>그 해 총 부여</b>(연말 기준)로 계산하되 —
 * 미리 당겨 쓰는 것을 허용하는 방침 — 아직 발생하지 않은 몫이 섞여 있다는 것을
 * 사람마다 안내한다.
 */
class LeaveFutureYearTest {

    private static final long USER = 7L;

    private final HrMapper hrMapper = mock(HrMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);

    private final HrService hrService = new HrService(
            hrMapper, mock(TripReportMapper.class), userMapper,
            mock(ApprovalService.class), mock(CommunicationService.class),
            mock(PanelRefreshPublisher.class), mock(HrScopeService.class),
            mock(ApprovalDocumentService.class), mock(LeaveAllowanceService.class), mock(LoaService.class));

    @BeforeEach
    void setUp() {
        lenient().when(hrMapper.findLeaveAccrualPolicy()).thenReturn(null);   // 입사일 기준
        lenient().when(hrMapper.findWeekSchedule()).thenReturn(List.of());
        lenient().when(hrMapper.findCompanyHolidays(any(), any())).thenReturn(List.of());
        lenient().when(hrMapper.findLeaveRequests(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(hrMapper.findBusinessTrips(any(), any(), anyInt())).thenReturn(List.of());
        givenLeaveType("ANNUAL", "연차", true);
    }

    /* ── 연도 기준 ────────────────────────────────────── */

    /*
      핵심 — 미리보기가 어느 해를 말하는지 스스로 밝혀야 화면이 그 해 값으로 갈아끼울 수 있다.
    */
    @Test
    @DisplayName("신청 날짜의 연도를 함께 내려 준다")
    void previewReportsTheYearItIsTalkingAbout() {
        givenUser(LocalDate.of(2015, 1, 1));
        givenBalance(2027, "20", "0");

        HrService.LeaveDaysPreview preview = preview(LocalDate.of(2027, 3, 2));

        assertThat(preview.year()).isEqualTo(2027);
    }

    /*
      내년 날짜를 골랐으면 잔여도 내년 원장에서 와야 한다.
      올해 원장을 보면 "3일 남았다"면서 5일을 승인해 주는 상태가 된다.
    */
    @Test
    @DisplayName("잔여는 신청 연도의 원장에서 가져온다")
    void remainingComesFromTheRequestedYear() {
        givenUser(LocalDate.of(2015, 1, 1));
        givenBalance(2026, "20", "17");    // 올해는 3일 남음
        givenBalance(2027, "20", "0");     // 내년은 아직 안 씀

        assertThat(preview(LocalDate.of(2026, 3, 2)).remaining()).isEqualByComparingTo("3");
        assertThat(preview(LocalDate.of(2027, 3, 2)).remaining()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("그 해 총 부여도 함께 내려 준다")
    void previewCarriesTheEntitlementForThatYear() {
        givenUser(LocalDate.of(2015, 1, 1));
        givenBalance(2027, "20", "0");

        assertThat(preview(LocalDate.of(2027, 3, 2)).entitlement()).isEqualByComparingTo("20");
    }

    /* ── 입사기념일 안내 ──────────────────────────────── */

    /*
      ★ 2014-06-01 입사자의 2027년:
          연말(12/31) 기준 근속 13년 → 15 + (13-1)/2 = 21일
          6/1 이전       근속 12년 → 15 + (12-1)/2 = 20일
        A안(그 해 총 부여로 계산, 미리 당겨쓰기 허용)이므로 21일로 계산하되,
        3월에 신청하는 사람에게는 아직 20일까지만 발생했다고 알려 준다.
    */
    @Test
    @DisplayName("입사기념일 전에 신청하면 아직 발생하지 않은 몫을 알려 준다")
    void warnsWhenPartOfTheEntitlementHasNotAccruedYet() {
        givenUser(LocalDate.of(2014, 6, 1));
        givenBalance(2027, "21", "0");

        HrService.LeaveDaysPreview preview = preview(LocalDate.of(2027, 3, 2));

        assertThat(preview.accrualNote())
                .contains("2027년 총 부여 21일")
                .contains("2027-06-01")
                .contains("20일");
    }

    @Test
    @DisplayName("입사기념일이 지난 뒤에는 안내하지 않는다")
    void noNoteAfterTheAnniversary() {
        givenUser(LocalDate.of(2014, 6, 1));
        givenBalance(2027, "21", "0");

        assertThat(preview(LocalDate.of(2027, 9, 2)).accrualNote()).isNull();
    }

    /*
      기념일이 가산 경계를 넘지 않는 해에는 전후가 같으므로 안내할 것이 없다.
      2015-01-01 입사자의 2027년은 1/1 기준으로도 12년차라 20일로 동일하다.
    */
    @Test
    @DisplayName("전후로 부여가 같으면 안내하지 않는다")
    void noNoteWhenNothingChanges() {
        givenUser(LocalDate.of(2015, 1, 1));
        givenBalance(2027, "20", "0");

        assertThat(preview(LocalDate.of(2027, 3, 2)).accrualNote()).isNull();
    }

    /*
      회계연도 기준은 1/1 에 부여가 확정되어 연중 변하지 않으므로 안내가 필요 없다.
    */
    @Test
    @DisplayName("회계연도 기준일 때는 안내하지 않는다")
    void noNoteUnderFiscalYearBasis() {
        com.portfolio.worksystem.model.LeaveAccrualPolicy policy = new com.portfolio.worksystem.model.LeaveAccrualPolicy();
        policy.setBasis("FISCAL_YEAR");
        policy.setEffectiveFromYear(2000);
        when(hrMapper.findLeaveAccrualPolicy()).thenReturn(policy);
        givenUser(LocalDate.of(2014, 6, 1));
        givenBalance(2027, "21", "0");

        assertThat(preview(LocalDate.of(2027, 3, 2)).accrualNote()).isNull();
    }

    @Test
    @DisplayName("입사일이 없으면 안내하지 않는다")
    void noNoteWithoutAHireDate() {
        givenUser(null);
        givenBalance(2027, "15", "0");

        assertThat(preview(LocalDate.of(2027, 3, 2)).accrualNote()).isNull();
    }

    /* ── 해를 걸친 신청 ───────────────────────────────── */

    /*
      ★ 예전에는 2026-12-28 ~ 2027-01-05 를 받아 <전부 2026년에서> 차감했다.
        1/1 이후 쓴 날까지 작년 몫에서 나갔고, 2027년 원장·집계에는 아예 안 잡혔다
        (조회가 YEAR(start_date) 기준이라서).
    */
    @Test
    @DisplayName("해를 걸친 신청은 나눠 내라고 막는다")
    void crossYearRequestIsRejectedWithGuidance() {
        givenUser(LocalDate.of(2015, 1, 1));
        givenBalance(2026, "20", "0");

        assertThatThrownBy(() -> hrService.requestLeave(user(),
                new LeaveRequestForm("ANNUAL", LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 5), "연말휴가")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2026-12-28 ~ 2026-12-31")
                .hasMessageContaining("2027-01-01 ~ 2027-01-05");

        verify(hrMapper, never()).insertLeaveRequest(any());
        verify(hrMapper, never()).increaseUsedLeave(any(), any(), any());
    }

    @Test
    @DisplayName("미리보기도 같은 말을 한다")
    void previewSaysTheSameThing() {
        givenUser(LocalDate.of(2015, 1, 1));

        HrService.LeaveDaysPreview preview = hrService.leaveDaysPreview(user(), "ANNUAL",
                LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 5));

        assertThat(preview.warnings()).anyMatch(w -> w.contains("해를 걸쳐"));
        assertThat(preview.days()).isEqualByComparingTo("0");
    }

    /* ── 도우미 ──────────────────────────────────────── */

    private HrService.LeaveDaysPreview preview(LocalDate date) {
        return hrService.leaveDaysPreview(user(), "ANNUAL", date, date);
    }

    private void givenUser(LocalDate hireDate) {
        UserAccount account = account(hireDate);
        lenient().when(userMapper.findById(USER)).thenReturn(account);
    }

    private void givenBalance(int year, String total, String used) {
        LeaveBalance balance = new LeaveBalance();
        balance.setId((long) year);
        balance.setUserId(USER);
        balance.setBalanceYear(year);
        balance.setTotalDays(new BigDecimal(total));
        balance.setUsedDays(new BigDecimal(used));
        balance.setAdjustedDays(BigDecimal.ZERO);
        lenient().when(hrMapper.findLeaveBalance(eq(USER), eq(year))).thenReturn(balance);
    }

    private void givenLeaveType(String code, String label, boolean deductAnnual) {
        LeaveTypeDef def = new LeaveTypeDef();
        def.setCode(code);
        def.setLabel(label);
        def.setDayWeight(new BigDecimal("1.00"));
        def.setDeductAnnual(deductAnnual);
        def.setActive(true);
        lenient().when(hrMapper.findLeaveTypeDef(code)).thenReturn(def);
    }

    private CustomUserDetails user() {
        return new CustomUserDetails(account(null));
    }

    private UserAccount account(LocalDate hireDate) {
        UserAccount user = new UserAccount();
        user.setId(USER);
        user.setUsername("kim");
        user.setName("김직원");
        user.setHireDate(hireDate);
        user.setRoles(List.of("USER"));
        return user;
    }
}
