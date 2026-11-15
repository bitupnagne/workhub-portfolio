package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.LoaMapper;
import com.portfolio.worksystem.mapper.TripReportMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.LoaRequest;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * [B7] 휴직을 연차 <b>출근율</b>에 반영한다.
 *
 * <p>예전에는 반영이 아예 없었다 — 1년 내내 휴직한 사람도 근속만 쌓여 연차가 그대로 나왔고,
 * 그게 연차수당 정산까지 그대로 갔다. 사유를 알 방법조차 없었다(날짜 두 개뿐이었다).
 *
 * <p>이제 휴직 종류마다 반영 방식이 붙는다.
 * <ul>
 *   <li><b>출근 간주</b> — 육아휴직·업무상 부상/질병. 법이 출근으로 보므로 연차가 깎이지 않는다.</li>
 *   <li><b>소정근로일 제외</b> — 그 밖의 휴직. 쉰 만큼 비례해서 줄어든다.</li>
 * </ul>
 *
 * <p>기준은 달력일이 아니라 <b>소정근로일</b>이다. 달력일로 재면 주말이 많이 낀 휴직이
 * 더 많이 깎인다.
 */
class LoaAccrualTest {

    private static final long USER = 7L;
    private static final int YEAR = 2025;

    private final HrMapper hrMapper = mock(HrMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final LoaMapper loaMapper = mock(LoaMapper.class);
    private final HrScopeService hrScopeService = mock(HrScopeService.class);

    // 휴직 서비스는 진짜를 쓴다 — '출근 간주는 세지 않는다'가 이 기능의 계약이라 함께 검증해야 한다.
    private final LoaService loaService = new LoaService(
            loaMapper, userMapper, mock(ApprovalService.class), hrScopeService,
            mock(UserChangeLogService.class));

    private final HrService hrService = new HrService(
            hrMapper, mock(TripReportMapper.class), userMapper,
            mock(ApprovalService.class), mock(CommunicationService.class),
            mock(PanelRefreshPublisher.class), hrScopeService,
            mock(ApprovalDocumentService.class), mock(LeaveAllowanceService.class), loaService);

    @BeforeEach
    void setUp() {
        lenient().when(hrMapper.findLeaveAccrualPolicy()).thenReturn(null);   // 입사일 기준
        lenient().when(hrMapper.findWeekSchedule()).thenReturn(List.of());    // 주 5일 기본
        lenient().when(hrMapper.findCompanyHolidays(any(), any())).thenReturn(List.of());
        lenient().when(hrMapper.findLeaveBalance(any(), anyInt())).thenReturn(null);
        lenient().when(loaMapper.findApprovedPeriodsInYear(anyInt())).thenReturn(List.of());
        // 2010-01-01 입사 → 2025년 연말 기준 근속 15년 → 15 + (15-1)/2 = 22일
        lenient().when(userMapper.findById(USER)).thenReturn(account(LocalDate.of(2010, 1, 1)));
    }

    /* ═══ 출근 간주 vs 제외 ═══════════════════════════ */

    @Nested
    @DisplayName("휴직 종류에 따른 반영")
    class ByTreatment {

        @Test
        @DisplayName("휴직이 없으면 그대로 22일")
        void baselineWithoutLeaveOfAbsence() {
            assertThat(entitlement()).isEqualByComparingTo("22");
        }

        /*
          ★ 핵심 — 육아휴직은 1년을 통째로 쉬어도 연차가 줄지 않는다.
            법이 출근한 것으로 보도록 정하고 있다.
        */
        @Test
        @DisplayName("육아휴직은 1년 내내 쉬어도 연차가 줄지 않는다")
        void parentalLeaveDoesNotReduceEntitlement() {
            givenLoa("COUNTED_AS_WORK", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

            assertThat(entitlement()).isEqualByComparingTo("22");
        }

        @Test
        @DisplayName("개인사정 휴직으로 1년 내내 쉬면 그 해 연차가 없다")
        void fullYearExcludedLeaveYieldsNothing() {
            givenLoa("EXCLUDED", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

            assertThat(entitlement()).isEqualByComparingTo("0");
        }

        /*
          2025년 상반기(1/1~6/30)를 쉬면 소정근로일의 약 절반이 빠진다.
          22일의 절반 언저리가 나와야 한다 — 정확한 값은 주말·달 길이에 따라 갈리므로
          범위로 확인하고, 0.5일 단위로 떨어지는지도 함께 본다.
        */
        @Test
        @DisplayName("반년을 쉬면 대략 절반으로 줄어든다")
        void halfYearRoughlyHalvesIt() {
            givenLoa("EXCLUDED", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30));

            java.math.BigDecimal days = entitlement();

            assertThat(days).isBetween(new java.math.BigDecimal("10"), new java.math.BigDecimal("12"));
            assertThat(days.multiply(java.math.BigDecimal.valueOf(2)).stripTrailingZeros().scale())
                    .as("0.5일 단위로 떨어져야 한다 — 연차는 반차까지만 쓴다").isLessThanOrEqualTo(0);
        }

        /*
          사유를 모르는 옛 데이터(loa_type 이 NULL)는 조인에서 빠져 아예 오지 않는다.
          와도 EXCLUDED 가 아니면 세지 않는다 — 모르는 채로 깎으면 육아휴직자가 손해를 본다.
        */
        @Test
        @DisplayName("사유를 알 수 없는 휴직은 연차를 깎지 않는다")
        void unknownTreatmentDoesNotReduce() {
            givenLoa(null, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

            assertThat(entitlement()).isEqualByComparingTo("22");
        }
    }

    /* ═══ 기간 자르기 ═════════════════════════════════ */

    @Nested
    @DisplayName("기간 계산")
    class Period {

        /*
          휴직이 해를 걸쳐 있어도 그 해에 걸친 부분만 센다.
        */
        @Test
        @DisplayName("전년도부터 이어진 휴직은 올해 몫만 센다")
        void carriedOverPeriodIsClippedToTheYear() {
            givenLoa("EXCLUDED", LocalDate.of(2024, 7, 1), LocalDate.of(2025, 6, 30));

            assertThat(entitlement()).isBetween(new java.math.BigDecimal("10"), new java.math.BigDecimal("12"));
        }

        /*
          복직일 미정(end_date NULL)은 열린 구간이다 — 그 해 끝까지로 본다.
        */
        @Test
        @DisplayName("복직일 미정이면 연말까지 쉰 것으로 본다")
        void openEndedPeriodRunsToYearEnd() {
            givenLoa("EXCLUDED", LocalDate.of(2025, 1, 1), null);

            assertThat(entitlement()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("다른 해 휴직은 올해에 영향이 없다")
        void otherYearsDoNotMatter() {
            when(loaMapper.findApprovedPeriodsInYear(YEAR)).thenReturn(List.of());

            assertThat(entitlement()).isEqualByComparingTo("22");
        }

        /*
          주말만 걸친 휴직은 소정근로일을 하나도 빼지 않는다 —
          달력일로 셌다면 이틀이 깎였을 것이다.
        */
        @Test
        @DisplayName("주말만 걸친 휴직은 연차를 깎지 않는다")
        void weekendOnlyLeaveChangesNothing() {
            // 2025-03-08(토) ~ 2025-03-09(일)
            givenLoa("EXCLUDED", LocalDate.of(2025, 3, 8), LocalDate.of(2025, 3, 9));

            assertThat(entitlement()).isEqualByComparingTo("22");
        }
    }

    /* ═══ 휴직 제외일 수집 ═══════════════════════════ */

    @Test
    @DisplayName("출근 간주 휴직은 제외일 목록에 담기지 않는다")
    void countedAsWorkIsNotCollected() {
        givenLoa("COUNTED_AS_WORK", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31));

        assertThat(loaService.excludedLoaDatesByUser(YEAR)).doesNotContainKey(USER);
    }

    /*
      신청서와 users 현재 휴직이 같은 기간을 중복해 담을 수 있다(인사담당자가 직접 넣은 건은
      신청서가 없고, 신청서로 승인된 건은 users 에도 반영된다). 날짜 집합이라 겹쳐도 한 번만 센다.
    */
    @Test
    @DisplayName("같은 기간이 두 번 와도 한 번만 센다")
    void duplicatePeriodsAreCountedOnce() {
        LoaRequest a = period("EXCLUDED", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 10));
        LoaRequest b = period("EXCLUDED", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 10));
        when(loaMapper.findApprovedPeriodsInYear(YEAR)).thenReturn(List.of(a, b));

        assertThat(loaService.excludedLoaDatesByUser(YEAR).get(USER)).hasSize(10);
    }

    /* ═══ [B8] 퇴사자 중도 정산 ═══════════════════════ */

    @Nested
    @DisplayName("퇴사 정산")
    class Resignation {

        /*
          ★ 6월 말에 그만둔 사람에게 연말 기준 22일을 그대로 두면, 쓰지도 못할 연차가
            미사용분으로 남아 연차수당이 과다 산정된다. 재직한 기간까지만 부여한다.
        */
        @Test
        @DisplayName("연중 퇴사하면 재직 기간까지만 부여된다")
        void midYearResignationProratesTheGrant() {
            givenResigned(LocalDate.of(2025, 6, 30));

            assertThat(entitlement()).isBetween(new java.math.BigDecimal("10"), new java.math.BigDecimal("12"));
        }

        @Test
        @DisplayName("연말에 퇴사하면 그 해는 온전히 부여된다")
        void yearEndResignationKeepsTheFullGrant() {
            givenResigned(LocalDate.of(2025, 12, 31));

            assertThat(entitlement()).isEqualByComparingTo("22");
        }

        /*
          작년에 이미 퇴사한 사람은 올해 재직일이 없다.
        */
        @Test
        @DisplayName("이전 해에 퇴사했으면 그 뒤 해는 부여가 없다")
        void resignedInAnEarlierYearYieldsNothing() {
            givenResigned(LocalDate.of(2024, 6, 30));

            assertThat(entitlement()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("퇴사일이 다음 해면 올해에는 영향이 없다")
        void futureResignationDoesNotAffectThisYear() {
            givenResigned(LocalDate.of(2026, 3, 31));

            assertThat(entitlement()).isEqualByComparingTo("22");
        }

        /*
          휴직과 퇴사가 함께 있으면 둘 다 빠진다 — 같은 날이 겹쳐도 한 번만 센다.
        */
        @Test
        @DisplayName("휴직과 퇴사가 겹쳐도 한 번만 뺀다")
        void leaveAndResignationOverlapCountOnce() {
            when(userMapper.findById(USER)).thenReturn(resignedAccount(LocalDate.of(2025, 6, 30)));
            givenLoa("EXCLUDED", LocalDate.of(2025, 5, 1), LocalDate.of(2025, 12, 31));

            // 5~6월 휴직분은 이미 퇴사 이후와 겹치므로, 실질 재직은 1~4월뿐이다.
            assertThat(entitlement()).isBetween(new java.math.BigDecimal("6"), new java.math.BigDecimal("8"));
        }

        private void givenResigned(LocalDate on) {
            when(userMapper.findById(USER)).thenReturn(resignedAccount(on));
        }
    }

    /* ── 도우미 ──────────────────────────────────────── */

    private UserAccount resignedAccount(LocalDate resignedOn) {
        UserAccount u = account(LocalDate.of(2010, 1, 1));
        u.setResignedOn(resignedOn);
        return u;
    }

    private java.math.BigDecimal entitlement() {
        return hrService.leaveBalance(user(), YEAR).getTotalDays();
    }

    private void givenLoa(String treatment, LocalDate from, LocalDate to) {
        when(loaMapper.findApprovedPeriodsInYear(YEAR)).thenReturn(List.of(period(treatment, from, to)));
    }

    private LoaRequest period(String treatment, LocalDate from, LocalDate to) {
        LoaRequest r = new LoaRequest();
        r.setUserId(USER);
        r.setAccrualTreatment(treatment);
        r.setStartDate(from);
        r.setEndDate(to);
        return r;
    }

    private CustomUserDetails user() {
        return new CustomUserDetails(account(LocalDate.of(2010, 1, 1)));
    }

    private UserAccount account(LocalDate hireDate) {
        UserAccount u = new UserAccount();
        u.setId(USER);
        u.setUsername("kim");
        u.setName("김직원");
        u.setHireDate(hireDate);
        u.setRoles(List.of("EMPLOYEE"));
        return u;
    }
}
