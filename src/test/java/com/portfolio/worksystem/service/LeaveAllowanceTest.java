package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.mapper.LeaveAllowanceMapper;
import com.portfolio.worksystem.model.EmpGrade;
import com.portfolio.worksystem.model.LeaveAllowanceRate;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * [D4] 연차수당 — 미사용 연차는 이월하지 않고 수당으로 지급한다.
 *
 * <p>이 파일이 지키는 것은 <b>"단가 미설정"과 "0원"을 절대 섞지 않는 것</b>이다.
 * 0원은 회사가 "이 등급은 수당 없음"이라고 정한 것이고, 미설정은 "아직 안 정했다"이다.
 * 후자를 0으로 조용히 계산하면 <b>줘야 할 돈을 안 준 채로</b> 정산이 끝나고, 화면에는
 * 아무 이상이 없어 보인다. 그래서 미설정은 금액을 내지 않고(null) 화면이 경고하게 한다.
 *
 * <p>단가를 연도별로 두는 이유도 같은 결이다 — 임금이 오를 때 단가를 덮어쓰면 이미 지급이
 * 끝난 과거 연도를 다시 뽑았을 때 금액이 소급해서 바뀐다.
 */
class LeaveAllowanceTest {

    private static final long G3 = 3L;   // 3급
    private static final long G5 = 5L;   // 5급

    private final LeaveAllowanceMapper allowanceMapper = mock(LeaveAllowanceMapper.class);
    private final EmpGradeMapper gradeMapper = mock(EmpGradeMapper.class);
    private final HrScopeService hrScopeService = mock(HrScopeService.class);

    // 등급 트리는 진짜를 쓴다 — 단가 상속(급 → 호봉)이 이 서비스의 계약이라 함께 검증해야 한다.
    private final EmpGradeService empGradeService = new EmpGradeService(gradeMapper);

    private final LeaveAllowanceService service =
            new LeaveAllowanceService(allowanceMapper, gradeMapper, hrScopeService, empGradeService);

    @BeforeEach
    void setUp() {
        when(gradeMapper.findAllWithUserCount()).thenReturn(List.of(grade(G3, "3급"), grade(G5, "5급")));
        when(hrScopeService.canManageHr(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    /* ═══ 금액 계산 ═══════════════════════════════════ */

    @Nested
    @DisplayName("수당 계산")
    class Calculation {

        @Test
        @DisplayName("미사용 일수 × 등급 단가")
        void unusedDaysTimesGradeRate() {
            Map<Long, BigDecimal> rates = Map.of(G3, new BigDecimal("180000"));

            assertThat(service.allowanceFor(G3, new BigDecimal("5"), rates))
                    .isEqualByComparingTo("900000");
        }

        @Test
        @DisplayName("반차가 섞여 0.5일 단위여도 계산된다")
        void halfDaysAreCalculated() {
            Map<Long, BigDecimal> rates = Map.of(G3, new BigDecimal("180000"));

            assertThat(service.allowanceFor(G3, new BigDecimal("2.5"), rates))
                    .isEqualByComparingTo("450000");
        }

        /*
          ★ 핵심 — 단가를 안 정한 등급은 0원이 아니라 <계산 못 함>이다.
            0으로 채우면 줘야 할 돈을 안 준 채로 정산이 끝나고 화면은 멀쩡해 보인다.
        */
        @Test
        @DisplayName("단가가 미설정이면 0원이 아니라 계산하지 않는다")
        void missingRateIsNotZero() {
            Map<Long, BigDecimal> rates = Map.of(G3, new BigDecimal("180000"));

            assertThat(service.allowanceFor(G5, new BigDecimal("5"), rates)).isNull();
        }

        @Test
        @DisplayName("등급이 지정되지 않은 직원도 계산하지 않는다")
        void missingGradeIsNotZero() {
            Map<Long, BigDecimal> rates = Map.of(G3, new BigDecimal("180000"));

            assertThat(service.allowanceFor(null, new BigDecimal("5"), rates)).isNull();
        }

        /*
          0원은 "수당 없음"이라고 <정한> 것이다. 미설정과 달리 정상적으로 0을 내야 한다.
        */
        @Test
        @DisplayName("단가를 0원으로 정한 등급은 0원을 낸다(미설정과 다르다)")
        void zeroRateIsAnAnswerNotAnAbsence() {
            Map<Long, BigDecimal> rates = Map.of(G3, BigDecimal.ZERO);

            assertThat(service.allowanceFor(G3, new BigDecimal("5"), rates))
                    .isNotNull()
                    .isEqualByComparingTo("0");
        }

        /*
          조정으로 잔여가 음수가 될 수 있다(관리자가 깎는 경우). 음수 수당은 만들지 않는다 —
          회사가 직원에게 돈을 청구하는 모양이 된다.
        */
        @Test
        @DisplayName("잔여가 음수면 0원으로 낸다 — 음수 수당은 만들지 않는다")
        void negativeRemainingYieldsZero() {
            Map<Long, BigDecimal> rates = Map.of(G3, new BigDecimal("180000"));

            assertThat(service.allowanceFor(G3, new BigDecimal("-2"), rates))
                    .isEqualByComparingTo("0");
        }
    }

    /* ═══ 단가 저장 ═══════════════════════════════════ */

    @Nested
    @DisplayName("단가 저장")
    class Saving {

        @Test
        @DisplayName("입력한 등급은 저장하고, 빈 등급은 미설정으로 되돌린다")
        void blankClearsInsteadOfSavingZero() {
            Map<Long, String> input = new LinkedHashMap<>();
            input.put(G3, "180000");
            input.put(G5, "");

            String message = service.saveRates(user(), 2026, input);

            verify(allowanceMapper).upsert(org.mockito.ArgumentMatchers.argThat(
                    r -> r.getGradeId().equals(G3)
                            && r.getDailyAmount().compareTo(new BigDecimal("180000")) == 0
                            && r.getRateYear() == 2026));
            verify(allowanceMapper).delete(2026, G5);
            assertThat(message).contains("설정 1건").contains("되돌림 1건");
        }

        @Test
        @DisplayName("천단위 콤마가 들어와도 받는다")
        void commasAreAccepted() {
            service.saveRates(user(), 2026, Map.of(G3, "180,000", G5, "140,000"));

            verify(allowanceMapper).upsert(org.mockito.ArgumentMatchers.argThat(
                    r -> r.getGradeId().equals(G3) && r.getDailyAmount().compareTo(new BigDecimal("180000")) == 0));
        }

        @Test
        @DisplayName("숫자가 아니면 등급명을 알려주며 거부한다")
        void nonNumericIsRejectedWithTheGradeName() {
            assertThatThrownBy(() -> service.saveRates(user(), 2026, Map.of(G3, "십팔만원")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("3급");

            verify(allowanceMapper, never()).upsert(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("음수 단가는 거부한다")
        void negativeRateIsRejected() {
            assertThatThrownBy(() -> service.saveRates(user(), 2026, Map.of(G3, "-1000")))
                    .isInstanceOf(BusinessException.class);
        }

        /*
          1일 단가 칸에 월급이나 연봉을 넣는 실수를 잡는다.
        */
        @Test
        @DisplayName("1억을 넘는 단가는 입력 실수로 보고 거부한다")
        void absurdlyLargeRateIsRejected() {
            assertThatThrownBy(() -> service.saveRates(user(), 2026, Map.of(G3, "999999999")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1일 단가");
        }

        @Test
        @DisplayName("HR 관리자가 아니면 저장할 수 없다")
        void onlyHrManagerCanSave() {
            when(hrScopeService.canManageHr(org.mockito.ArgumentMatchers.any())).thenReturn(false);

            assertThatThrownBy(() -> service.saveRates(user(), 2026, Map.of(G3, "180000")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("권한");
        }
    }

    /* ═══ 화면용 목록 ═════════════════════════════════ */

    @Nested
    @DisplayName("원장 단일 등급 단가 수정")
    class SingleSaving {
        @Test
        void updatingOneGradeNeverClearsOtherGradeRates() {
            service.saveRate(user(), 2026, G3, "180,000");
            verify(allowanceMapper).upsert(org.mockito.ArgumentMatchers.argThat(rate ->
                    rate.getGradeId().equals(G3) && rate.getRateYear() == 2026
                            && rate.getDailyAmount().compareTo(new BigDecimal("180000")) == 0
                            && rate.getUpdatedBy().equals(user().getUserId())));
            verify(allowanceMapper, never()).delete(anyInt(), org.mockito.ArgumentMatchers.anyLong());
            org.mockito.Mockito.verifyNoMoreInteractions(allowanceMapper);
        }

        @Test
        void clearingOneGradeRestoresInheritanceWithoutTouchingOtherGradesOrYears() {
            service.saveRate(user(), 2025, G3, "");
            verify(allowanceMapper).delete(2025, G3);
            org.mockito.Mockito.verifyNoMoreInteractions(allowanceMapper);
        }

        @Test
        void explicitZeroIsSavedRatherThanCleared() {
            service.saveRate(user(), 2026, G3, "0");
            verify(allowanceMapper).upsert(org.mockito.ArgumentMatchers.argThat(rate ->
                    rate.getGradeId().equals(G3) && rate.getDailyAmount().signum() == 0));
            org.mockito.Mockito.verifyNoMoreInteractions(allowanceMapper);
        }

        @Test
        void hrPermissionIsRequiredBeforeAnyRateWrite() {
            when(hrScopeService.canManageHr(org.mockito.ArgumentMatchers.any())).thenReturn(false);
            assertThatThrownBy(() -> service.saveRate(user(), 2026, G3, "1000"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("권한");
            org.mockito.Mockito.verifyNoInteractions(allowanceMapper);
        }

        @Test
        void inactiveOrUnknownGradeCannotBeChanged() {
            assertThatThrownBy(() -> service.saveRate(user(), 2026, 999L, "1000"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("활성 등급");
            org.mockito.Mockito.verifyNoInteractions(allowanceMapper);
        }

        @Test
        void invalidYearOrAmountDoesNotWrite() {
            assertThatThrownBy(() -> service.saveRate(user(), 1999, G3, "1000"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.saveRate(user(), 2026, G3, "-1000"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.saveRate(user(), 2026, G3, "not-a-number"))
                    .isInstanceOf(BusinessException.class);
            org.mockito.Mockito.verifyNoInteractions(allowanceMapper);
        }
    }

    /*
      단가를 안 정한 등급이 목록에서 <빠지면> 관리자가 그 등급을 영영 못 채운다.
      활성 등급은 전부 나오되, 안 정한 것은 금액이 비어 있어야 한다.
    */
    @Test
    @DisplayName("단가를 안 정한 등급도 목록에 빈 값으로 나온다")
    void unsetGradesStillAppearInTheList() {
        LeaveAllowanceRate saved = new LeaveAllowanceRate();
        saved.setGradeId(G3);
        saved.setRateYear(2026);
        saved.setDailyAmount(new BigDecimal("180000"));
        when(allowanceMapper.findByYear(2026)).thenReturn(List.of(saved));

        List<LeaveAllowanceRate> rows = service.ratesForYear(2026);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getGradeName()).isEqualTo("3급");
        assertThat(rows.get(0).getDailyAmount()).isEqualByComparingTo("180000");
        assertThat(rows.get(1).getGradeName()).isEqualTo("5급");
        assertThat(rows.get(1).getDailyAmount()).isNull();
    }

    /*
      단가를 한 번도 안 넣은 회사도 올해를 골라 입력할 수 있어야 한다.
    */
    @Test
    @DisplayName("연도 선택지에는 저장 이력이 없어도 올해가 들어 있다")
    void yearChoicesAlwaysIncludeThisYear() {
        when(allowanceMapper.findYearsWithRates()).thenReturn(List.of());

        assertThat(service.selectableYears()).contains(service.currentYear());
    }

    @Test
    @DisplayName("연도 선택지는 최신순이고 중복되지 않는다")
    void yearChoicesAreSortedAndDistinct() {
        int thisYear = service.currentYear();
        when(allowanceMapper.findYearsWithRates()).thenReturn(List.of(thisYear, 2023));

        List<Integer> years = service.selectableYears();

        assertThat(years).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(years).contains(2023, thisYear, thisYear + 1);
    }

    @Test
    @DisplayName("먼 미래 연도는 저장할 수 없다")
    void farFutureYearIsRejected() {
        assertThatThrownBy(() -> service.saveRates(user(), service.currentYear() + 5, Map.of(G3, "180000")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("연도");
    }

    /* ═══ 트리 상속 (V83) ═════════════════════════════ */

    /*
      ★ 직급이 트리가 되면 단가도 상속한다 — '3급'에만 넣어 두면 그 아래 호봉이 전부 그 단가를
        쓴다. 상속이 없으면 호봉을 만드는 순간 전 직원 단가가 미설정이 되어, 정산이 통째로
        비어 버린다(0원이 아니라 '미산출').
    */
    @Test
    @DisplayName("호봉은 급의 단가를 물려받는다")
    void hobongInheritsTheGradeRate() {
        when(gradeMapper.findAllWithUserCount()).thenReturn(List.of(
                grade(G3, "3급"), grade(100L, G3, "1호봉"), grade(101L, G3, "2호봉")));
        LeaveAllowanceRate onGrade = new LeaveAllowanceRate();
        onGrade.setGradeId(G3);
        onGrade.setRateYear(2026);
        onGrade.setDailyAmount(new BigDecimal("180000"));
        when(allowanceMapper.findByYear(2026)).thenReturn(List.of(onGrade));

        Map<Long, BigDecimal> rates = service.rateByGrade(2026);

        assertThat(rates.get(100L)).isEqualByComparingTo("180000");
        assertThat(rates.get(101L)).isEqualByComparingTo("180000");
    }

    @Test
    @DisplayName("호봉에 직접 넣은 단가가 상속을 이긴다")
    void ownRateWinsOverInherited() {
        when(gradeMapper.findAllWithUserCount()).thenReturn(List.of(
                grade(G3, "3급"), grade(100L, G3, "1호봉"), grade(101L, G3, "2호봉")));
        LeaveAllowanceRate onGrade = new LeaveAllowanceRate();
        onGrade.setGradeId(G3);
        onGrade.setRateYear(2026);
        onGrade.setDailyAmount(new BigDecimal("180000"));
        LeaveAllowanceRate onHobong = new LeaveAllowanceRate();
        onHobong.setGradeId(101L);
        onHobong.setRateYear(2026);
        onHobong.setDailyAmount(new BigDecimal("190000"));
        when(allowanceMapper.findByYear(2026)).thenReturn(List.of(onGrade, onHobong));

        Map<Long, BigDecimal> rates = service.rateByGrade(2026);

        assertThat(rates.get(100L)).isEqualByComparingTo("180000");
        assertThat(rates.get(101L)).isEqualByComparingTo("190000");
    }

    @Test
    @DisplayName("조상 어디에도 단가가 없으면 여전히 미설정이다")
    void noRateAnywhereStaysUnset() {
        when(gradeMapper.findAllWithUserCount()).thenReturn(List.of(
                grade(G5, "5급"), grade(200L, G5, "1호봉")));
        when(allowanceMapper.findByYear(2026)).thenReturn(List.of());

        assertThat(service.rateByGrade(2026)).doesNotContainKey(200L);
    }

    /* ── 도우미 ──────────────────────────────────────── */

    private EmpGrade grade(Long id, String name) {
        return grade(id, null, name);
    }

    private EmpGrade grade(Long id, Long parentId, String name) {
        EmpGrade grade = new EmpGrade();
        grade.setId(id);
        grade.setParentId(parentId);
        grade.setName(name);
        grade.setActive(true);
        grade.setDisplayOrder(id.intValue());
        return grade;
    }

    private CustomUserDetails user() {
        UserAccount account = new UserAccount();
        account.setId(1L);
        account.setUsername("hr");
        account.setName("인사담당");
        account.setRoles(List.of("HR_ADMIN"));
        return new CustomUserDetails(account);
    }
}
