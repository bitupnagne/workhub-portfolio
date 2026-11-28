package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.mapper.OvertimeMapper;
import com.portfolio.worksystem.mapper.PayrollMapper;
import com.portfolio.worksystem.model.EmpGrade;
import com.portfolio.worksystem.model.GradeHourlyWage;
import com.portfolio.worksystem.model.OvertimeSetting;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** [PAY] 직급별 통상시급 — 적용일별 이력, 상위 직급 상속, 미설정은 0원이 아님. */
class PayrollServiceTest {
    private static final long G3 = 3L;
    private static final long G3_1 = 31L;
    private static final long G3_2 = 32L;
    private static final long G5 = 5L;

    private final PayrollMapper payrollMapper = mock(PayrollMapper.class);
    private final EmpGradeMapper gradeMapper = mock(EmpGradeMapper.class);
    private final OvertimeMapper overtimeMapper = mock(OvertimeMapper.class);
    private final AdminPermissionService permissions = mock(AdminPermissionService.class);
    private final EmpGradeService grades = new EmpGradeService(gradeMapper,
            mock(com.portfolio.worksystem.mapper.UserMapper.class), mock(com.portfolio.worksystem.mapper.TeamMapper.class));
    private final PayrollService service = new PayrollService(payrollMapper, grades, overtimeMapper, permissions);
    private final List<GradeHourlyWage> wages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(gradeMapper.findAllWithUserCount()).thenReturn(List.of(
                grade(G3, null, "3급"), grade(G3_1, G3, "1호봉"), grade(G3_2, G3, "2호봉"), grade(G5, null, "5급")));
        when(payrollMapper.findAllWages()).thenReturn(wages);
        OvertimeSetting setting = new OvertimeSetting();
        setting.setDailyStandardHours(8);
        when(overtimeMapper.findSetting()).thenReturn(setting);
    }

    @Test
    void theWageOfADayIsTheLatestOneStartedByThatDay() {
        wages.add(wage(G3, "2026-01-01", 15000));
        wages.add(wage(G3, "2027-01-01", 16000));

        assertThat(service.hourlyWage(G3, LocalDate.of(2026, 12, 31))).isEqualByComparingTo("15000");
        assertThat(service.hourlyWage(G3, LocalDate.of(2027, 1, 1))).isEqualByComparingTo("16000");
        // 시작 전이면 아직 없다
        assertThat(service.hourlyWage(G3, LocalDate.of(2025, 12, 31))).isNull();
    }

    @Test
    void childGradesInheritUntilTheyHaveTheirOwn() {
        wages.add(wage(G3, "2026-01-01", 15000));
        wages.add(wage(G3_2, "2026-07-01", 15500));

        var at = service.wagesAt(LocalDate.of(2026, 8, 1));
        assertThat(at.get(G3_1).hourlyWage()).isEqualByComparingTo("15000");
        assertThat(at.get(G3_1).inherited()).isTrue();
        assertThat(at.get(G3_2).hourlyWage()).isEqualByComparingTo("15500");
        assertThat(at.get(G3_2).inherited()).isFalse();
        // 2호봉도 자기 값이 시작되기 전에는 상위 값을 쓴다
        assertThat(service.hourlyWage(G3_2, LocalDate.of(2026, 6, 30))).isEqualByComparingTo("15000");
        // 아무 데도 없으면 0원이 아니라 미설정
        assertThat(at).doesNotContainKey(G5);
    }

    @Test
    void leaveAllowanceDailyAmountIsWageTimesStandardHours() {
        wages.add(wage(G3, "2026-01-01", 15000));

        assertThat(service.dailyAmountsAt(LocalDate.of(2026, 12, 31)).get(G3)).isEqualByComparingTo("120000");
    }

    @Test
    void onlyPayrollManagersCanSaveAndAmountsAreValidated() {
        CustomUserDetails actor = actor();
        when(permissions.can(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.saveWage(actor, G3, LocalDate.of(2026, 1, 1), "15000", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("권한");

        when(permissions.can(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.saveWage(actor, G3, LocalDate.of(2026, 1, 1), "0", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveWage(actor, G3, LocalDate.of(2026, 1, 1), "만오천", null))
                .isInstanceOf(BusinessException.class);
        verify(payrollMapper, never()).upsertWage(any());

        assertThat(service.saveWage(actor, G3, LocalDate.of(2026, 1, 1), "15,000원", "인상")).contains("15,000원");
        verify(payrollMapper).upsertWage(any());
    }

    private static GradeHourlyWage wage(long gradeId, String from, long amount) {
        GradeHourlyWage wage = new GradeHourlyWage();
        wage.setGradeId(gradeId);
        wage.setEffectiveFrom(LocalDate.parse(from));
        wage.setHourlyWage(BigDecimal.valueOf(amount));
        return wage;
    }

    private static EmpGrade grade(long id, Long parent, String name) {
        EmpGrade grade = new EmpGrade();
        grade.setId(id);
        grade.setParentId(parent);
        grade.setName(name);
        grade.setActive(true);
        grade.setDisplayOrder((int) id);
        return grade;
    }

    private static CustomUserDetails actor() {
        UserAccount account = new UserAccount();
        account.setId(1L);
        account.setUsername("pay");
        account.setName("급여담당");
        account.setRoles(List.of("EMPLOYEE"));
        return new CustomUserDetails(account);
    }
}
