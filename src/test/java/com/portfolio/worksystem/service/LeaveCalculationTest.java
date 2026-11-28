package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.TripReportMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.AttendanceWeekDay;
import com.portfolio.worksystem.model.CompanyHoliday;
import com.portfolio.worksystem.model.LeaveAccrualPolicy;
import com.portfolio.worksystem.model.LeaveBalance;
import com.portfolio.worksystem.model.LeaveTypeDef;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 연차 <b>부여</b>와 <b>차감</b> 계산을 고정한다.
 *
 * <p>둘 다 사람의 금전적 권리를 직접 정하는데 테스트가 없었다. 부여는 입사일과 부여기준
 * 정책(V77)에서, 차감은 요일별 근무시간표와 회사 휴무일에서 나온다. 어느 쪽이든 하루가
 * 틀리면 잔여 연차가 하루 틀린다.
 *
 * <p>계산 자체는 {@code private} 이라 공개 진입점으로 몬다 —
 * 부여는 {@code leaveEntitlementSummary}, 차감은 {@code leaveDaysPreview}(신청 검증인
 * {@code calculateLeaveDays} 와 같은 규칙을 쓴다).
 *
 * <p><b>기준일이 연도마다 달라지므로</b> 과거 연도만 쓴다. 당해 연도는 기준일이
 * {@code LocalDate.now()} 라서 테스트가 오늘 날짜에 묶인다.
 */
class LeaveCalculationTest {

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
        // 기본값: 정책 미설정(=입사일 기준), 평범한 주 5일, 휴무일·기존신청 없음
        lenient().when(hrMapper.findLeaveAccrualPolicy()).thenReturn(null);
        lenient().when(hrMapper.findWeekSchedule()).thenReturn(List.of());
        lenient().when(hrMapper.findCompanyHolidays(any(), any())).thenReturn(List.of());
        lenient().when(hrMapper.findLeaveRequests(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(hrMapper.findBusinessTrips(any(), any(), anyInt())).thenReturn(List.of());
    }

    /* ═══ 부여 ═══════════════════════════════════════════ */

    @Nested
    @DisplayName("연차 부여(입사일 기준)")
    class HireDateBasis {

        @Test
        @DisplayName("입사일이 없으면 기본 15일")
        void noHireDateFallsBackToFifteen() {
            assertThat(entitledDays(null, 2020)).isEqualTo("15");
        }

        @Test
        @DisplayName("기준일보다 늦게 입사했으면 0일")
        void hiredAfterReferenceDateGetsNothing() {
            assertThat(entitledDays(LocalDate.of(2021, 1, 1), 2020)).isEqualTo("0");
        }

        /*
          근속 1년 미만은 법정상 월 단위로 발생한다(1개월 개근 시 1일).
          코드는 만 개월 수를 그대로 쓰되 11일을 넘지 않게 자른다.
        */
        @Test
        @DisplayName("근속 1년 미만은 만 개월 수만큼 (3월 입사 → 그해 9일)")
        void underOneYearAccruesMonthly() {
            assertThat(entitledDays(LocalDate.of(2020, 3, 1), 2020)).isEqualTo("9");
        }

        @Test
        @DisplayName("근속 1년~2년은 가산 없이 15일")
        void oneYearGetsBaseFifteen() {
            assertThat(entitledDays(LocalDate.of(2019, 12, 1), 2020)).isEqualTo("15");
        }

        /*
          가산휴가는 3년차부터 2년마다 1일 — 코드는 (근속연수-1)/2 로 계산한다.
          근속 10년 → (10-1)/2 = 4일 가산 → 19일.
        */
        @Test
        @DisplayName("가산휴가는 2년마다 1일 (근속 10년 → 15+4 = 19일)")
        void extraDayEveryTwoYears() {
            assertThat(entitledDays(LocalDate.of(2010, 1, 1), 2020)).isEqualTo("19");
        }

        /*
          상한(max_annual_days)을 두지 않으면 근속에 따라 계속 늘어난다 — 기본 동작.
          근로기준법 제60조 4항의 25일 한도를 강제하고 싶으면 관리자가 상한을 넣어야 한다
          (아래 EntitlementCap 참고).
        */
        @Test
        @DisplayName("상한을 두지 않으면 근속에 따라 계속 늘어난다 — 근속 25년이면 27일")
        void withoutACapTheEntitlementKeepsGrowing() {
            assertThat(entitledDays(LocalDate.of(2000, 1, 1), 2025)).isEqualTo("27");
        }
    }

    /* ═══ 부여 상한 (V81) ═══════════════════════════════ */

    @Nested
    @DisplayName("연차 부여 상한")
    class EntitlementCap {

        private static final LocalDate LONG_TENURE = LocalDate.of(2000, 1, 1);   // 2025 기준 근속 25년

        @Test
        @DisplayName("상한을 넣으면 가산휴가가 그 위로 올라가지 않는다")
        void capLimitsTheAccruedTotal() {
            assertThat(entitledDays(LONG_TENURE, 2025)).isEqualTo("27");   // 상한 없음

            givenCap(25);

            assertThat(entitledDays(LONG_TENURE, 2025)).isEqualTo("25");
        }

        @Test
        @DisplayName("상한에 못 미치는 사람은 영향을 받지 않는다")
        void capDoesNotRaiseAnyoneUp() {
            givenCap(25);

            // 근속 10년 → 15+4 = 19. 상한 25 와 무관하다.
            assertThat(entitledDays(LocalDate.of(2010, 1, 1), 2020)).isEqualTo("19");
        }

        /*
          근속 1년 미만 월차는 법정상 매월 개근 시 1일씩 별도로 발생한다.
          가산휴가 상한은 이 구간에 적용하지 않는다.
        */
        @Test
        @DisplayName("근속 1년 미만 월차에는 상한을 적용하지 않는다")
        void capDoesNotTouchMonthlyAccrual() {
            givenCap(15);

            assertThat(entitledDays(LocalDate.of(2020, 3, 1), 2020)).isEqualTo("9");
        }

        @Test
        @DisplayName("상한이 걸린 사람은 산식 문구가 '25(상한)'으로 나온다")
        void cappedEntitlementSaysSo() {
            givenCap(25);

            HrService.LeaveEntitlementSummary summary = summaryOf(LONG_TENURE, 2025);

            assertThat(summary.entitlementDays()).isEqualTo("25");
            assertThat(summary.entitlementText()).isEqualTo("25(상한)");
        }

        private void givenCap(int cap) {
            LeaveAccrualPolicy policy = new LeaveAccrualPolicy();
            policy.setBasis("HIRE_DATE");
            policy.setEffectiveFromYear(2000);
            policy.setMaxAnnualDays(cap);
            when(hrMapper.findLeaveAccrualPolicy()).thenReturn(policy);
        }
    }

    @Nested
    @DisplayName("연차 부여(회계연도 기준, V77)")
    class FiscalYearBasis {

        private static final LocalDate HIRE = LocalDate.of(2009, 6, 1);

        /*
          회계연도 기준은 1/1 을 기준일로 못박는다 — 연중에 입사기념일이 지나도
          잔여가 늘지 않게 하려는 것이다. 그래서 입사월이 연초가 아니면
          입사일 기준보다 근속연수가 한 해 적게 잡힐 수 있다.

            2020-01-01 기준 근속 10년 → 15 + 4 = 19
            2020-12-31 기준 근속 11년 → 15 + 5 = 20
        */
        @Test
        @DisplayName("1/1을 기준일로 삼는다 (입사일 기준 20일 → 회계연도 기준 19일)")
        void fiscalYearPinsReferenceToJanuaryFirst() {
            assertThat(entitledDays(HIRE, 2020)).isEqualTo("20");   // 정책 없음 = 입사일 기준

            applyFiscalYearPolicyFrom(2020);

            assertThat(entitledDays(HIRE, 2020)).isEqualTo("19");
        }

        @Test
        @DisplayName("적용 시작 연도 이전에는 예전 기준(입사일)을 그대로 쓴다")
        void policyDoesNotApplyBeforeItsEffectiveYear() {
            applyFiscalYearPolicyFrom(2025);

            assertThat(entitledDays(HIRE, 2020)).isEqualTo("20");
        }

        /*
          1년 미만 월차는 법정상 매월 발생이라 부여기준과 무관하게 유지되어야 한다.
          1/1 시점에 근속 1년이 안 되면 회계연도 기준을 적용하지 않고 예전 방식으로 넘긴다.
        */
        @Test
        @DisplayName("1/1에 근속 1년 미만이면 월차 발생 방식을 유지한다")
        void monthlyAccrualSurvivesTheFiscalYearPolicy() {
            applyFiscalYearPolicyFrom(2020);

            assertThat(entitledDays(LocalDate.of(2020, 3, 1), 2020)).isEqualTo("9");
        }

        /*
          ★ 핵심 회귀 — 화면 문구와 실제 부여일수는 절대 어긋나면 안 된다.

            예전에는 산식 문구(entitlementText)를 근속연수로 <다시 계산>해서 만들었다.
            그래서 회계연도 기준이 켜지면 문구는 여전히 입사일 기준(12/31)으로 계산돼
            "20(15+5)" 라고 써놓고 19일을 주는 상태가 됐다.

            이제 문구는 부여일수 하나에서 파생된다. 어긋날 방법이 없다.
        */
        @Test
        @DisplayName("산식 문구가 실제 부여일수와 일치한다")
        void entitlementExpressionMatchesTheActualDays() {
            applyFiscalYearPolicyFrom(2020);

            HrService.LeaveEntitlementSummary summary = summaryOf(HIRE, 2020);

            assertThat(summary.entitlementDays()).isEqualTo("19");
            assertThat(summary.entitlementText()).isEqualTo("19(15+4)");
        }

        private void applyFiscalYearPolicyFrom(int year) {
            LeaveAccrualPolicy policy = new LeaveAccrualPolicy();
            policy.setBasis("FISCAL_YEAR");
            policy.setEffectiveFromYear(year);
            when(hrMapper.findLeaveAccrualPolicy()).thenReturn(policy);
        }
    }

    /* ═══ 차감 ═══════════════════════════════════════════ */

    @Nested
    @DisplayName("연차 차감 일수")
    class DeductedDays {

        private static final LocalDate MON = LocalDate.of(2025, 3, 3);
        private static final LocalDate FRI = LocalDate.of(2025, 3, 7);
        private static final LocalDate SUN = LocalDate.of(2025, 3, 9);

        @Test
        @DisplayName("월~금 전일 휴가는 5일")
        void fullWeekCountsFiveDays() {
            givenLeaveType("SPECIAL", "1.00", false);

            assertThat(daysOf("SPECIAL", MON, FRI)).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("주말은 차감하지 않는다 (월~일 신청도 5일)")
        void weekendsAreNotDeducted() {
            givenLeaveType("SPECIAL", "1.00", false);

            HrService.LeaveDaysPreview preview = hrService.leaveDaysPreview(user(null), "SPECIAL", MON, SUN);

            assertThat(preview.days()).isEqualByComparingTo("5");
            assertThat(preview.warnings()).anyMatch(w -> w.contains("휴무 요일"));
        }

        @Test
        @DisplayName("회사 휴무일은 차감하지 않는다 (수요일 휴무 → 4일)")
        void companyHolidaysAreNotDeducted() {
            givenLeaveType("SPECIAL", "1.00", false);
            when(hrMapper.findCompanyHolidays(any(), any()))
                    .thenReturn(List.of(holiday(LocalDate.of(2025, 3, 5), "창립기념일")));

            assertThat(daysOf("SPECIAL", MON, FRI)).isEqualByComparingTo("4");
        }

        /*
          주 4.5일제 등 반일(HALF) 근무 요일은 반나절만 차감한다.
        */
        @Test
        @DisplayName("반일 근무 요일은 0.5일만 차감한다 (금요일 반일 → 4.5일)")
        void halfWorkdayCountsHalf() {
            givenLeaveType("SPECIAL", "1.00", false);
            when(hrMapper.findWeekSchedule()).thenReturn(List.of(weekDay(5, "HALF")));

            assertThat(daysOf("SPECIAL", MON, FRI)).isEqualByComparingTo("4.5");
        }

        @Test
        @DisplayName("반차는 0.5일")
        void halfDayLeaveCountsHalf() {
            givenLeaveType("AM_HALF", "0.50", true);

            assertThat(daysOf("AM_HALF", MON, MON)).isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("반차를 이틀 이상으로 신청하면 경고로 막는다")
        void halfDayLeaveCannotSpanDays() {
            givenLeaveType("AM_HALF", "0.50", true);

            HrService.LeaveDaysPreview preview = hrService.leaveDaysPreview(user(null), "AM_HALF", MON, FRI);

            assertThat(preview.days()).isEqualByComparingTo("0");
            assertThat(preview.warnings()).contains("반차는 하루만 신청할 수 있습니다.");
        }

        /*
          반일 근무 요일의 반차는 그날 근무 전체와 같다. 0.5 를 넘겨 차감하면
          하루치 근무보다 많이 깎이므로 가중치를 넘지 않게 자른다.
        */
        @Test
        @DisplayName("반일 근무 요일의 반차도 0.5일을 넘지 않는다")
        void halfDayLeaveOnAHalfWorkdayIsCapped() {
            givenLeaveType("AM_HALF", "0.50", true);
            when(hrMapper.findWeekSchedule()).thenReturn(List.of(weekDay(1, "HALF")));

            assertThat(daysOf("AM_HALF", MON, MON)).isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("차감 대상이 아닌 휴가는 잔여를 건드리지 않는다")
        void nonDeductingTypeLeavesTheBalanceAlone() {
            givenLeaveType("SPECIAL", "1.00", false);

            HrService.LeaveDaysPreview preview = hrService.leaveDaysPreview(user(null), "SPECIAL", MON, FRI);

            assertThat(preview.annualCounted()).isFalse();
            assertThat(preview.remaining()).isNull();
            assertThat(preview.remainingAfter()).isNull();
        }

        /*
          잔여는 원장(부여+조정-사용)에서 나오되, 부여분은 조회할 때마다 입사일 기준으로
          다시 계산해 덮어쓴다. 2010-01-01 입사 → 2025년 부여 22일, 이미 20일 사용 → 잔여 2일.
        */
        @Test
        @DisplayName("차감 대상이면 잔여와 신청 후 잔여를 계산하고, 모자라면 경고한다")
        void deductingTypeReportsRemainingAndWarnsWhenShort() {
            givenLeaveType("ANNUAL", "1.00", true);
            when(userMapper.findById(USER)).thenReturn(account(LocalDate.of(2010, 1, 1)));
            when(hrMapper.findLeaveBalance(eq(USER), eq(2025))).thenReturn(balance("0", "20"));

            HrService.LeaveDaysPreview preview = hrService.leaveDaysPreview(user(null), "ANNUAL", MON, FRI);

            assertThat(preview.annualCounted()).isTrue();
            assertThat(preview.remaining()).isEqualByComparingTo("2");      // 22 부여 - 20 사용
            assertThat(preview.remainingAfter()).isEqualByComparingTo("-3"); // 5일 신청
            assertThat(preview.warnings()).contains("잔여 연차가 부족합니다.");
        }

        private BigDecimal daysOf(String type, LocalDate from, LocalDate to) {
            return hrService.leaveDaysPreview(user(null), type, from, to).days();
        }

        private void givenLeaveType(String code, String dayWeight, boolean deductAnnual) {
            LeaveTypeDef def = new LeaveTypeDef();
            def.setCode(code);
            def.setLabel(code);
            def.setDayWeight(new BigDecimal(dayWeight));
            def.setDeductAnnual(deductAnnual);
            def.setActive(true);
            when(hrMapper.findLeaveTypeDef(code)).thenReturn(def);
        }
    }

    /* ── 도우미 ──────────────────────────────────────── */

    private String entitledDays(LocalDate hireDate, int year) {
        return summaryOf(hireDate, year).entitlementDays();
    }

    private HrService.LeaveEntitlementSummary summaryOf(LocalDate hireDate, int year) {
        when(userMapper.findById(USER)).thenReturn(account(hireDate));
        return hrService.leaveEntitlementSummary(user(hireDate), year);
    }

    private CustomUserDetails user(LocalDate hireDate) {
        return new CustomUserDetails(account(hireDate));
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

    private LeaveBalance balance(String adjusted, String used) {
        LeaveBalance balance = new LeaveBalance();
        balance.setId(1L);
        balance.setUserId(USER);
        balance.setBalanceYear(2025);
        balance.setAdjustedDays(new BigDecimal(adjusted));
        balance.setUsedDays(new BigDecimal(used));
        return balance;
    }

    private CompanyHoliday holiday(LocalDate date, String name) {
        CompanyHoliday holiday = new CompanyHoliday();
        holiday.setHolidayDate(date);
        holiday.setName(name);
        return holiday;
    }

    private AttendanceWeekDay weekDay(int dayOfWeek, String workType) {
        AttendanceWeekDay day = new AttendanceWeekDay();
        day.setDayOfWeek(dayOfWeek);
        day.setWorkType(workType);
        return day;
    }
}
