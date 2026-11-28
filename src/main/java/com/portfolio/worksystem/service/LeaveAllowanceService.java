package com.portfolio.worksystem.service;

import com.portfolio.worksystem.model.EmpGrade;
import com.portfolio.worksystem.model.LeaveAllowanceRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [D4] 연차수당 — <b>미사용 연차는 이월하지 않고 수당으로 지급한다.</b>
 *
 * <p>단가는 직급(등급)마다 다르고, 임금이 매년 오르므로 <b>연도별로</b> 따로 보관한다.
 * 단가를 한 벌만 두면 인상할 때 덮어써야 하고, 그러면 이미 지급이 끝난 과거 연도를 다시
 * 뽑았을 때 금액이 소급해서 바뀐다. 지급된 금액은 나중에 조회해도 그대로여야 한다.
 *
 * <p><b>단가 미설정과 0원은 다르다.</b> 0원은 "수당 없음"이라고 정한 것이고, 미설정은
 * "아직 안 정했다"이다. 후자를 0으로 조용히 계산하면 <b>줘야 할 돈을 안 준 채로</b>
 * 정산이 끝난다. 그래서 미설정은 금액을 내지 않고({@code null}) 화면이 경고하게 한다.
 */
@Service
@RequiredArgsConstructor
public class LeaveAllowanceService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final EmpGradeService empGradeService;
    private final PayrollService payrollService;

    public int currentYear() {
        return Year.now(SERVICE_ZONE).getValue();
    }

    /**
     * [PAY] 해당 연도의 직급별 1일 단가 — <b>활성 직급 전부</b>를 직급 순서대로 돌려준다.
     * 단가는 따로 입력하지 않는다: 정산 기준일(연말)의 통상시급 × 기준 시간(급여 관리).
     * 통상시급이 없는 직급은 {@code dailyAmount = null}(0원이 아니다).
     */
    @Transactional(readOnly = true)
    public List<LeaveAllowanceRate> ratesForYear(int year) {
        Map<Long, BigDecimal> daily = rateByGrade(year);
        List<LeaveAllowanceRate> rows = new ArrayList<>();
        for (EmpGrade grade : empGradeService.activeTree()) {
            LeaveAllowanceRate row = new LeaveAllowanceRate();
            row.setRateYear(year);
            row.setGradeId(grade.getId());
            row.setDailyAmount(daily.get(grade.getId()));
            row.setGradeName(grade.getName());
            row.setPathName(grade.getPathName());
            row.setDepth(grade.getDepth());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 직급 → 1일 단가(= 정산 기준일의 통상시급 × 기준 시간). 통상시급이 없는 직급은 <b>키가 없다</b>.
     * 상위 직급 상속·적용일 이력은 급여 관리(PayrollService)가 처리한다.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> rateByGrade(int year) {
        return payrollService.dailyAmountsAt(settlementDate(year));
    }

    /**
     * 미사용 일수 × 단가. 계산할 수 없으면 null 을 돌려준다 —
     * 직급 미지정이거나 그 직급 통상시급이 미설정인 경우다. <b>0원으로 대신 채우지 않는다.</b>
     */
    public BigDecimal allowanceFor(Long gradeId, BigDecimal unusedDays, Map<Long, BigDecimal> rates) {
        if (gradeId == null || unusedDays == null) {
            return null;
        }
        BigDecimal daily = rates.get(gradeId);
        if (daily == null) {
            return null;
        }
        // 잔여가 음수(조정으로 깎인 경우)면 지급할 것이 없다 — 음수 수당은 만들지 않는다.
        BigDecimal days = unusedDays.max(BigDecimal.ZERO);
        return daily.multiply(days).setScale(0, java.math.RoundingMode.DOWN);
    }

    /** 정산 기준일 — 해당 연도 말일. 그날의 통상시급으로 연차수당을 계산한다. */
    public LocalDate settlementDate(int year) {
        return LocalDate.of(year, 12, 31);
    }
}
