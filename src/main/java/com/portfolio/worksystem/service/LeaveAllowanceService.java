package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.mapper.LeaveAllowanceMapper;
import com.portfolio.worksystem.model.EmpGrade;
import com.portfolio.worksystem.model.LeaveAllowanceRate;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private final LeaveAllowanceMapper leaveAllowanceMapper;
    private final EmpGradeMapper empGradeMapper;
    private final HrScopeService hrScopeService;
    private final EmpGradeService empGradeService;

    public int currentYear() {
        return Year.now(SERVICE_ZONE).getValue();
    }

    /**
     * 해당 연도의 등급별 단가 — <b>활성 등급 전부</b>를 등급 순서대로 돌려준다.
     * 아직 안 정한 등급은 {@code dailyAmount = null} 로 나온다(0원이 아니다).
     */
    @Transactional(readOnly = true)
    public List<LeaveAllowanceRate> ratesForYear(int year) {
        Map<Long, LeaveAllowanceRate> saved = new LinkedHashMap<>();
        for (LeaveAllowanceRate rate : leaveAllowanceMapper.findByYear(year)) {
            saved.put(rate.getGradeId(), rate);
        }
        List<LeaveAllowanceRate> rows = new ArrayList<>();
        for (EmpGrade grade : empGradeService.activeTree()) {
            LeaveAllowanceRate row = saved.get(grade.getId());
            if (row == null) {
                row = new LeaveAllowanceRate();
                row.setRateYear(year);
                row.setGradeId(grade.getId());
            }
            row.setGradeName(grade.getName());
            row.setPathName(grade.getPathName());
            row.setDepth(grade.getDepth());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 등급 → 1일 단가. 미설정 등급은 <b>키가 없다</b>(0으로 채우지 않는다).
     *
     * <p>[D5] 직급이 트리이므로 단가도 <b>상속</b>한다 — '3급'에만 넣어 두면 그 아래 호봉이
     * 전부 그 단가를 쓴다. 호봉마다 금액이 다르면 그 노드에 직접 넣으면 되고, 그 값이 이긴다.
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> rateByGrade(int year) {
        Map<Long, BigDecimal> own = new LinkedHashMap<>();
        for (LeaveAllowanceRate rate : leaveAllowanceMapper.findByYear(year)) {
            own.put(rate.getGradeId(), rate.getDailyAmount());
        }
        Map<Long, BigDecimal> resolved = new LinkedHashMap<>();
        Map<Long, Long> parentOf = new LinkedHashMap<>();
        for (EmpGrade grade : empGradeService.tree()) {
            parentOf.put(grade.getId(), grade.getParentId());
        }
        for (Long id : parentOf.keySet()) {
            BigDecimal amount = own.get(id);
            Long cursor = parentOf.get(id);
            int guard = 0;
            while (amount == null && cursor != null && guard++ < 100) {
                amount = own.get(cursor);
                cursor = parentOf.get(cursor);
            }
            if (amount != null) {
                resolved.put(id, amount);
            }
        }
        return resolved;
    }

    /** 단가를 정한 연도들 + 올해·내년 — 화면 연도 선택지. */
    @Transactional(readOnly = true)
    public List<Integer> selectableYears() {
        List<Integer> years = new ArrayList<>(leaveAllowanceMapper.findYearsWithRates());
        int thisYear = currentYear();
        for (int candidate : new int[]{thisYear + 1, thisYear, thisYear - 1}) {
            if (!years.contains(candidate)) {
                years.add(candidate);
            }
        }
        years.sort(java.util.Comparator.reverseOrder());
        return years;
    }

    /**
     * 등급별 단가 저장. 값이 비어 있으면 그 등급은 <b>미설정으로 되돌린다</b>(0원 저장이 아니다).
     *
     * @param amounts 등급 id → 입력값(빈 문자열이면 미설정)
     */
    @Transactional
    public String saveRates(CustomUserDetails currentUser, int year, Map<Long, String> amounts) {
        requireHrManager(currentUser);
        validateYear(year);
        int saved = 0;
        int cleared = 0;
        for (EmpGrade grade : empGradeService.activeTree()) {
            String raw = amounts.get(grade.getId());
            if (raw == null || raw.isBlank()) {
                leaveAllowanceMapper.delete(year, grade.getId());
                cleared++;
                continue;
            }
            BigDecimal amount = parseAmount(raw, grade.getName());
            LeaveAllowanceRate rate = new LeaveAllowanceRate();
            rate.setRateYear(year);
            rate.setGradeId(grade.getId());
            rate.setDailyAmount(amount);
            rate.setUpdatedBy(currentUser.getUserId());
            leaveAllowanceMapper.upsert(rate);
            saved++;
        }
        return year + "년 연차수당 단가를 저장했습니다. 설정 " + saved + "건"
                + (cleared > 0 ? ", 미설정으로 되돌림 " + cleared + "건" : "") + ".";
    }

    /** 원장 한 행에서 연도·등급 하나만 수정한다. 다른 등급의 설정은 건드리지 않는다. */
    @Transactional
    public String saveRate(CustomUserDetails currentUser, int year, Long gradeId, String rawAmount) {
        requireHrManager(currentUser);
        validateYear(year);
        EmpGrade grade = empGradeService.activeTree().stream()
                .filter(candidate -> candidate.getId().equals(gradeId))
                .findFirst().orElseThrow(() -> new BusinessException("활성 등급을 확인하세요."));
        if (rawAmount == null || rawAmount.isBlank()) {
            leaveAllowanceMapper.delete(year, gradeId);
            return year + "년 '" + grade.getName() + "' 단가를 비웠습니다. 상위 직급 단가를 따릅니다.";
        }
        LeaveAllowanceRate rate = new LeaveAllowanceRate();
        rate.setRateYear(year);
        rate.setGradeId(gradeId);
        rate.setDailyAmount(parseAmount(rawAmount, grade.getName()));
        rate.setUpdatedBy(currentUser.getUserId());
        leaveAllowanceMapper.upsert(rate);
        return year + "년 '" + grade.getName() + "' 연차수당 단가를 저장했습니다.";
    }

    private void validateYear(int year) {
        int thisYear = currentYear();
        if (year < 2000 || year > thisYear + 1) {
            throw new BusinessException("연도를 확인하세요(2000~" + (thisYear + 1) + ").");
        }
    }

    /**
     * 미사용 일수 × 단가. 계산할 수 없으면 null 을 돌려준다 —
     * 등급 미지정이거나 그 연도 단가가 미설정인 경우다. <b>0원으로 대신 채우지 않는다.</b>
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

    private BigDecimal parseAmount(String raw, String gradeName) {
        // 화면에서 천단위 콤마가 들어올 수 있다.
        String cleaned = raw.trim().replace(",", "").replace("원", "").trim();
        BigDecimal amount;
        try {
            amount = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new BusinessException("'" + gradeName + "' 단가는 숫자로 입력하세요.");
        }
        if (amount.signum() < 0) {
            throw new BusinessException("'" + gradeName + "' 단가는 0원 이상이어야 합니다.");
        }
        if (amount.compareTo(new BigDecimal("100000000")) > 0) {
            throw new BusinessException("'" + gradeName + "' 단가가 너무 큽니다. 1일 단가를 확인하세요.");
        }
        return amount.setScale(0, java.math.RoundingMode.DOWN);
    }

    private void requireHrManager(CustomUserDetails currentUser) {
        if (!hrScopeService.canManageHr(currentUser)) {
            throw new BusinessException("HR 관리자 권한이 필요합니다.");
        }
    }

    /** 정산 기준일 안내용 — 해당 연도 말일. */
    public LocalDate settlementDate(int year) {
        return LocalDate.of(year, 12, 31);
    }
}
