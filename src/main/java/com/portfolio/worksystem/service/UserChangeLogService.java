package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.UserChangeLogMapper;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.model.UserChangeLog;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [B9] 인사정보가 <b>누가 언제 무엇을 무엇으로</b> 바꿨는지 남긴다.
 *
 * <p>직원 정보의 변경 전후 값을 보관해 소속·직급·연차의 기준을 확인할 수 있게 한다.
 *
 * <p>수정 경로마다 흩어 놓지 않고 <b>저장 전후를 비교</b>하는 방식으로 모았다. 경로가 늘어도
 * 앞뒤 스냅샷만 넘기면 되고, 필드를 추가로 기록하려면 {@link #TRACKED} 에 한 줄만 더한다.
 *
 * <p>값은 id 가 아니라 <b>그때의 표시값</b>으로 남긴다 — 팀 id 3 보다 '플랫폼개발팀'이 읽히고,
 * 그 팀이 나중에 사라지거나 이름이 바뀌어도 이력은 당시를 그대로 말한다.
 */
@Service
@RequiredArgsConstructor
public class UserChangeLogService {

    /** 이력을 남길 항목 — 코드 → (표시 이름, 값 뽑는 법). */
    private static final Map<String, Tracked> TRACKED = new LinkedHashMap<>();

    static {
        track("name", "이름", UserAccount::getName);
        track("employeeNumber", "사번", UserAccount::getEmployeeNumber);
        track("teamName", "소속", UserAccount::getTeamName);
        track("gradeName", "직급", UserAccount::getGradeName);
        track("jobTitle", "직책", UserAccount::getJobTitle);
        track("dept", "부서 표기", UserAccount::getDept);
        track("extensionNumber", "내선번호", UserAccount::getExtensionNumber);
        track("mobileNumber", "휴대폰", UserAccount::getMobileNumber);
        track("email", "이메일", UserAccount::getEmail);
        track("hireDate", "입사일", u -> text(u.getHireDate()));
        track("resignedOn", "퇴사일", u -> text(u.getResignedOn()));
        track("contractor", "도급 여부", u -> Boolean.TRUE.equals(u.getContractor()) ? "도급" : "직영");
        track("active", "재직 여부", u -> Boolean.TRUE.equals(u.getActive()) ? "재직" : "비활성(퇴직)");
        track("loaStart", "휴직 시작일", u -> text(u.getLoaStart()));
        track("loaEnd", "휴직 종료일", u -> text(u.getLoaEnd()));
        track("loaType", "휴직 사유", UserAccount::getLoaType);
    }

    private final UserChangeLogMapper userChangeLogMapper;

    @Transactional(readOnly = true)
    public List<UserChangeLog> historyForUser(Long userId, int limit) {
        return userChangeLogMapper.findByUser(userId, limit);
    }

    @Transactional(readOnly = true)
    public int countForUser(Long userId) {
        return userChangeLogMapper.countByUser(userId);
    }

    /**
     * 저장 <b>전후</b>를 비교해 달라진 항목만 남긴다.
     *
     * <p>바뀐 게 없으면 아무것도 쓰지 않는다 — 저장 버튼만 눌러도 이력이 쌓이면 정작 중요한
     * 변경이 묻힌다.
     *
     * @param before 수정 전 스냅샷(null 이면 아무것도 남기지 않는다)
     * @param after  수정 후 값
     * @param source SELF / ADMIN / HR_CARD / TRANSFER / LOA / SYSTEM
     */
    @Transactional
    public int record(UserAccount before, UserAccount after, String source, CustomUserDetails actor) {
        if (before == null || after == null) {
            return 0;
        }
        Long actorId = actor == null ? null : actor.getUserId();
        int written = 0;
        for (Map.Entry<String, Tracked> entry : TRACKED.entrySet()) {
            String oldValue = entry.getValue().reader().apply(before);
            String newValue = entry.getValue().reader().apply(after);
            if (Objects.equals(normalize(oldValue), normalize(newValue))) {
                continue;
            }
            write(after.getId(), entry.getKey(), entry.getValue().label(), oldValue, newValue, source, actorId);
            written++;
        }
        return written;
    }

    /**
     * 필드 비교로는 드러나지 않는 <b>사건</b>을 한 줄로 남긴다 — 휴직 승인, 퇴사 처리처럼
     * 여러 값이 한꺼번에 바뀌면서 하나의 사건으로 읽히는 편이 나은 경우다.
     */
    @Transactional
    public void recordEvent(Long userId, String field, String label, String oldValue, String newValue,
                            String source, CustomUserDetails actor) {
        write(userId, field, label, oldValue, newValue, source, actor == null ? null : actor.getUserId());
    }

    private void write(Long userId, String field, String label, String oldValue, String newValue,
                       String source, Long actorId) {
        UserChangeLog log = new UserChangeLog();
        log.setUserId(userId);
        log.setField(field);
        log.setFieldLabel(label);
        log.setOldValue(clip(oldValue));
        log.setNewValue(clip(newValue));
        log.setSource(source);
        log.setChangedBy(actorId);
        userChangeLogMapper.insert(log);
    }

    /** null 과 빈 문자열은 같은 것으로 본다 — '없음'을 두 가지로 적어 이력이 늘어나면 안 된다. */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void track(String field, String label, Function<UserAccount, String> reader) {
        TRACKED.put(field, new Tracked(label, reader));
    }

    private record Tracked(String label, Function<UserAccount, String> reader) {
    }

    /** 수정 전 스냅샷 — 매퍼가 돌려준 객체를 그대로 들고 있으면 같은 인스턴스가 수정돼 비교가 무의미해진다. */
    public static UserAccount snapshot(UserAccount user) {
        if (user == null) {
            return null;
        }
        UserAccount copy = new UserAccount();
        copy.setId(user.getId());
        copy.setName(user.getName());
        copy.setEmployeeNumber(user.getEmployeeNumber());
        copy.setTeamId(user.getTeamId());
        copy.setTeamName(user.getTeamName());
        copy.setGradeId(user.getGradeId());
        copy.setGradeName(user.getGradeName());
        copy.setJobTitle(user.getJobTitle());
        copy.setDept(user.getDept());
        copy.setExtensionNumber(user.getExtensionNumber());
        copy.setMobileNumber(user.getMobileNumber());
        copy.setEmail(user.getEmail());
        copy.setHireDate(user.getHireDate());
        copy.setResignedOn(user.getResignedOn());
        copy.setContractor(user.getContractor());
        copy.setActive(user.getActive());
        copy.setLoaStart(user.getLoaStart());
        copy.setLoaEnd(user.getLoaEnd());
        copy.setLoaType(user.getLoaType());
        return copy;
    }

    /** 화면·이력 표기에 쓸 항목 목록(설명용). */
    public static List<String> trackedLabels() {
        return new ArrayList<>(TRACKED.values().stream().map(Tracked::label).toList());
    }
}
