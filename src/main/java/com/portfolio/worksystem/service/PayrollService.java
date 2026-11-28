package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.OvertimeMapper;
import com.portfolio.worksystem.mapper.PayrollMapper;
import com.portfolio.worksystem.model.AdminMenu;
import com.portfolio.worksystem.model.EmpGrade;
import com.portfolio.worksystem.model.GradeHourlyWage;
import com.portfolio.worksystem.model.OvertimeSetting;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PAY] 직급별 통상시급 — 시간외 수당과 연차수당이 같은 값을 본다.
 *
 * <ul>
 *   <li><b>적용일별 이력</b>: '이날부터 이 금액'으로 쌓는다. 어떤 날의 시급은 그날까지 시작된 것 중 가장 최근 값이다.
 *       그래서 인상해도 지난달 수당·작년 연차수당이 소급해서 바뀌지 않는다.</li>
 *   <li><b>상속</b>: 그 직급에 값이 없으면 가장 가까운 상위 직급 값을 쓴다('3급'에만 넣으면 아래 호봉 전부).
 *       호봉마다 다르면 그 호봉에 넣으면 되고, 그 값이 이긴다.</li>
 *   <li><b>미설정은 0원이 아니다</b>: 어디에도 값이 없으면 null — 화면이 '미설정'으로 경고한다.</li>
 * </ul>
 * 연차수당 1일 단가 = 통상시급 × 기준 시간(시간외 산정 기준의 '1일 기준 시간', 기본 8시간).
 */
@Service
@RequiredArgsConstructor
public class PayrollService {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final BigDecimal MAX_HOURLY = new BigDecimal("1000000");

    private final PayrollMapper payrollMapper;
    private final EmpGradeService empGradeService;
    private final OvertimeMapper overtimeMapper;
    private final AdminPermissionService permissionService;

    /** 어떤 직급의 어떤 날 시급과 그 출처. */
    public record Resolved(BigDecimal hourlyWage, Long sourceGradeId, String sourceGradeName,
                           LocalDate effectiveFrom, boolean inherited) {
    }

    /** 급여 관리 화면 한 줄. */
    public record Row(EmpGrade grade, Resolved current, GradeHourlyWage next, List<GradeHourlyWage> history) {
        public boolean isMissing() {
            return current == null;
        }
    }

    public LocalDate today() {
        return LocalDate.now(SERVICE_ZONE);
    }

    public boolean canManage(CustomUserDetails user) {
        return permissionService.can(user, AdminMenu.PAYROLL);
    }

    /** 연차수당 1일 단가를 만들 때 곱하는 시간. */
    @Transactional(readOnly = true)
    public int dailyStandardHours() {
        try {
            OvertimeSetting setting = overtimeMapper.findSetting();
            Integer hours = setting == null ? null : setting.getDailyStandardHours();
            return hours == null || hours <= 0 ? 8 : hours;
        } catch (RuntimeException e) {
            return 8;
        }
    }

    /** 그날 모든 직급의 시급(상속 반영). 값을 못 찾은 직급은 키가 없다. */
    @Transactional(readOnly = true)
    public Map<Long, Resolved> wagesAt(LocalDate date) {
        Map<Long, List<GradeHourlyWage>> own = historyByGrade();
        Map<Long, Resolved> result = new LinkedHashMap<>();
        Map<Long, EmpGrade> byId = new HashMap<>();
        List<EmpGrade> tree = empGradeService.tree();
        tree.forEach(grade -> byId.put(grade.getId(), grade));
        for (EmpGrade grade : tree) {
            Long cursor = grade.getId();
            int guard = 0;
            while (cursor != null && guard++ < 100) {
                GradeHourlyWage found = effective(own.get(cursor), date);
                if (found != null) {
                    EmpGrade source = byId.get(cursor);
                    result.put(grade.getId(), new Resolved(found.getHourlyWage(), cursor,
                            source == null ? found.getGradeName() : source.getPathName(),
                            found.getEffectiveFrom(), !cursor.equals(grade.getId())));
                    break;
                }
                EmpGrade node = byId.get(cursor);
                cursor = node == null ? null : node.getParentId();
            }
        }
        return result;
    }

    /** 그 직급의 그날 시급(없으면 null). */
    @Transactional(readOnly = true)
    public BigDecimal hourlyWage(Long gradeId, LocalDate date) {
        if (gradeId == null || date == null) {
            return null;
        }
        Resolved resolved = wagesAt(date).get(gradeId);
        return resolved == null ? null : resolved.hourlyWage();
    }

    /** 그날 직급별 연차수당 1일 단가 = 시급 × 기준 시간. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> dailyAmountsAt(LocalDate date) {
        BigDecimal hours = BigDecimal.valueOf(dailyStandardHours());
        Map<Long, BigDecimal> daily = new LinkedHashMap<>();
        wagesAt(date).forEach((gradeId, resolved) ->
                daily.put(gradeId, resolved.hourlyWage().multiply(hours).setScale(0, RoundingMode.DOWN)));
        return daily;
    }

    /** 급여 관리 화면 — 직급 트리 순서, 기준일의 시급·다음 예정 변경·이력. */
    @Transactional(readOnly = true)
    public List<Row> rows(LocalDate date) {
        Map<Long, List<GradeHourlyWage>> own = historyByGrade();
        Map<Long, Resolved> current = wagesAt(date);
        List<Row> rows = new ArrayList<>();
        for (EmpGrade grade : empGradeService.tree()) {
            List<GradeHourlyWage> history = own.getOrDefault(grade.getId(), List.of());
            GradeHourlyWage next = history.stream().filter(w -> w.getEffectiveFrom().isAfter(date)).findFirst().orElse(null);
            rows.add(new Row(grade, current.get(grade.getId()), next, history));
        }
        return rows;
    }

    @Transactional
    public String saveWage(CustomUserDetails actor, Long gradeId, LocalDate effectiveFrom, String rawAmount, String note) {
        requireManager(actor);
        EmpGrade grade = empGradeService.tree().stream().filter(g -> g.getId().equals(gradeId)).findFirst()
                .orElseThrow(() -> new BusinessException("없는 직급입니다."));
        if (effectiveFrom == null) {
            throw new BusinessException("적용 시작일을 입력하세요.");
        }
        BigDecimal amount = parseAmount(rawAmount);
        GradeHourlyWage wage = new GradeHourlyWage();
        wage.setGradeId(gradeId);
        wage.setEffectiveFrom(effectiveFrom);
        wage.setHourlyWage(amount);
        wage.setNote(note == null || note.isBlank() ? null : note.trim());
        wage.setCreatedBy(actor.getUserId());
        payrollMapper.upsertWage(wage);
        payrollMapper.insertLog(gradeId, grade.getPathName(), "SAVE", effectiveFrom, amount, actor.getUserId(), actor.getName());
        return "'" + grade.getPathName() + "' 통상시급을 " + effectiveFrom + "부터 " + String.format("%,d", amount.longValue())
                + "원으로 저장했습니다.";
    }

    @Transactional
    public String deleteWage(CustomUserDetails actor, Long wageId) {
        requireManager(actor);
        GradeHourlyWage wage = payrollMapper.findWage(wageId);
        if (wage == null) {
            throw new BusinessException("이미 지워진 내역입니다.");
        }
        payrollMapper.deleteWage(wageId);
        payrollMapper.insertLog(wage.getGradeId(), wage.getGradeName(), "DELETE", wage.getEffectiveFrom(),
                wage.getHourlyWage(), actor.getUserId(), actor.getName());
        return "'" + wage.getGradeName() + "'의 " + wage.getEffectiveFrom() + " 시급 내역을 지웠습니다.";
    }

    private Map<Long, List<GradeHourlyWage>> historyByGrade() {
        Map<Long, List<GradeHourlyWage>> own = new HashMap<>();
        for (GradeHourlyWage wage : payrollMapper.findAllWages()) {
            own.computeIfAbsent(wage.getGradeId(), key -> new ArrayList<>()).add(wage);
        }
        own.values().forEach(list -> list.sort(java.util.Comparator.comparing(GradeHourlyWage::getEffectiveFrom)));
        return own;
    }

    /** 그날까지 시작된 것 중 가장 최근 값(목록은 시작일 오름차순). */
    static GradeHourlyWage effective(List<GradeHourlyWage> history, LocalDate date) {
        if (history == null) {
            return null;
        }
        GradeHourlyWage found = null;
        for (GradeHourlyWage wage : history) {
            if (!wage.getEffectiveFrom().isAfter(date)) {
                found = wage;
            }
        }
        return found;
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("통상시급을 입력하세요.");
        }
        String cleaned = raw.trim().replace(",", "").replace("원", "").trim();
        BigDecimal amount;
        try {
            amount = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new BusinessException("통상시급은 숫자로 입력하세요.");
        }
        if (amount.signum() <= 0) {
            throw new BusinessException("통상시급은 0원보다 커야 합니다.");
        }
        if (amount.compareTo(MAX_HOURLY) > 0) {
            throw new BusinessException("통상시급이 너무 큽니다. 1시간 금액을 확인하세요.");
        }
        return amount.setScale(0, RoundingMode.DOWN);
    }

    private void requireManager(CustomUserDetails actor) {
        if (!canManage(actor)) {
            throw new BusinessException("급여 관리 권한이 없습니다.");
        }
    }
}
