package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.model.EmpGrade;
import com.portfolio.worksystem.model.LeaveAllowanceRate;
import java.math.BigDecimal;
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

    private final EmpGradeMapper gradeMapper = mock(EmpGradeMapper.class);
    private final PayrollService payrollService = mock(PayrollService.class);

    // 등급 트리는 진짜를 쓴다 — 화면 행이 직급 트리 순서로 나와야 한다.
    private final EmpGradeService empGradeService = new EmpGradeService(
            gradeMapper, mock(com.portfolio.worksystem.mapper.UserMapper.class),
            mock(com.portfolio.worksystem.mapper.TeamMapper.class));

    private final LeaveAllowanceService service = new LeaveAllowanceService(empGradeService, payrollService);

    @BeforeEach
    void setUp() {
        when(gradeMapper.findAllWithUserCount()).thenReturn(List.of(grade(G3, "3급"), grade(G5, "5급")));
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

    /* ═══ [PAY] 단가는 급여 관리에서 ═══════════════════ */

    @Nested
    @DisplayName("1일 단가 = 연말 통상시급 × 기준 시간")
    class FromPayroll {

        @Test
        @DisplayName("연차수당 단가는 그 해 마지막 날의 통상시급으로 만든다")
        void usesTheSettlementDateWage() {
            when(payrollService.dailyAmountsAt(java.time.LocalDate.of(2026, 12, 31)))
                    .thenReturn(Map.of(G3, new BigDecimal("120000")));

            assertThat(service.rateByGrade(2026)).containsEntry(G3, new BigDecimal("120000"));
            List<LeaveAllowanceRate> rows = service.ratesForYear(2026);
            assertThat(rows).extracting(LeaveAllowanceRate::getGradeId).containsExactly(G3, G5);
            assertThat(rows.get(0).getDailyAmount()).isEqualByComparingTo("120000");
            // 시급을 안 정한 직급은 0원이 아니라 미설정(null)
            assertThat(rows.get(1).getDailyAmount()).isNull();
        }
    }

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

}
