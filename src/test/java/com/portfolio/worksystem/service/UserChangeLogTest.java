package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.portfolio.worksystem.mapper.UserChangeLogMapper;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.model.UserChangeLog;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * [B9] 인사정보가 <b>누가 언제 무엇을 무엇으로</b> 바뀌었는지 남는다.
 *
 * <p>인사이동만 기록되고 나머지는 흔적이 없었다 — 조직관리에서 팀을 직접 바꾸거나, 직급을
 * 바꾸거나, 본인이 마이페이지에서 연락처를 고치면 아무 기록도 남지 않았다. 인사 정보는
 * 급여·평가·연차의 근거라서 "언제부터 이랬는지"를 되짚을 수 있어야 한다.
 *
 * <p>수정 경로마다 흩지 않고 <b>저장 전후 비교</b>로 모았다. 그래서 이 파일이 지키는 것은
 * 주로 <b>무엇을 이력으로 볼 것인가</b>의 경계다 — 안 바뀐 것을 남기지 않고, 같은 '없음'을
 * 두 가지로 적지 않는다.
 */
class UserChangeLogTest {

    private static final long USER = 7L;

    private final UserChangeLogMapper mapper = mock(UserChangeLogMapper.class);
    private final UserChangeLogService service = new UserChangeLogService(mapper);

    private UserAccount before;

    @BeforeEach
    void setUp() {
        before = user();
    }

    /* ── 무엇을 남기는가 ─────────────────────────────── */

    @Test
    @DisplayName("바뀐 항목만 남긴다")
    void onlyChangedFieldsAreRecorded() {
        UserAccount after = user();
        after.setJobTitle("팀장");
        after.setMobileNumber("010-9999-8888");

        int written = service.record(before, after, "ADMIN", actor());

        assertThat(written).isEqualTo(2);
        List<UserChangeLog> logs = captured(2);
        assertThat(logs).extracting(UserChangeLog::getFieldLabel).containsExactlyInAnyOrder("직책", "휴대폰");
    }

    /*
      저장 버튼만 눌러도 이력이 쌓이면 정작 중요한 변경이 묻힌다.
    */
    @Test
    @DisplayName("바뀐 게 없으면 아무것도 남기지 않는다")
    void noChangeNoLog() {
        assertThat(service.record(before, user(), "ADMIN", actor())).isZero();

        verify(mapper, never()).insert(any());
    }

    /*
      null 과 빈 문자열은 사람에게 같은 '없음'이다. 다르게 보면 저장할 때마다 이력이 생긴다.
    */
    @Test
    @DisplayName("빈 값과 null 은 같은 것으로 본다")
    void blankAndNullAreTheSame() {
        before.setDept(null);
        UserAccount after = user();
        after.setDept("   ");

        assertThat(service.record(before, after, "SELF", actor())).isZero();
    }

    @Test
    @DisplayName("이전 값과 이후 값을 함께 남긴다")
    void bothSidesAreRecorded() {
        UserAccount after = user();
        after.setTeamName("플랫폼개발팀");

        service.record(before, after, "ADMIN", actor());

        UserChangeLog log = captured(1).get(0);
        assertThat(log.getOldValue()).isEqualTo("운영지원팀");
        assertThat(log.getNewValue()).isEqualTo("플랫폼개발팀");
        assertThat(log.getFieldLabel()).isEqualTo("소속");
    }

    /*
      ★ 팀·직급은 id 로 바뀌지만 이력에는 <이름>을 남긴다. 나중에 그 팀이 사라지거나
        이름이 바뀌어도 이력은 당시를 그대로 말해야 한다.
    */
    @Test
    @DisplayName("직급은 id 가 아니라 이름으로 남는다")
    void gradeIsRecordedByName() {
        UserAccount after = user();
        after.setGradeId(9L);
        after.setGradeName("2급");

        service.record(before, after, "ADMIN", actor());

        UserChangeLog log = captured(1).get(0);
        assertThat(log.getFieldLabel()).isEqualTo("직급");
        assertThat(log.getOldValue()).isEqualTo("3급");
        assertThat(log.getNewValue()).isEqualTo("2급");
    }

    @Test
    @DisplayName("퇴사일·재직여부도 이력에 남는다")
    void resignationIsRecorded() {
        UserAccount after = user();
        after.setResignedOn(LocalDate.of(2026, 6, 30));
        after.setActive(false);

        service.record(before, after, "HR_CARD", actor());

        assertThat(captured(2)).extracting(UserChangeLog::getFieldLabel)
                .containsExactlyInAnyOrder("퇴사일", "재직 여부");
    }

    /* ── 경로 구분 ───────────────────────────────────── */

    /*
      "본인이 고쳤는지 관리자가 고쳤는지"가 이력의 핵심 정보다.
    */
    @Test
    @DisplayName("어느 경로로 바뀌었는지 함께 남는다")
    void sourceIsRecorded() {
        UserAccount after = user();
        after.setEmail("new@example.com");

        service.record(before, after, "SELF", actor());

        UserChangeLog log = captured(1).get(0);
        assertThat(log.getSource()).isEqualTo("SELF");
        assertThat(log.getSourceLabel()).isEqualTo("본인 수정");
        assertThat(log.getChangedBy()).isEqualTo(99L);
    }

    @Test
    @DisplayName("경로 이름은 사람이 읽는 말로 나온다")
    void sourceLabelsAreReadable() {
        UserChangeLog log = new UserChangeLog();
        log.setSource("TRANSFER");
        assertThat(log.getSourceLabel()).isEqualTo("소속 변경");
        log.setSource("HR_CARD");
        assertThat(log.getSourceLabel()).isEqualTo("관리자 (인사카드)");
    }

    /*
      휴직 승인은 사유·시작일·종료일이 한꺼번에 바뀌지만 사람이 읽을 때는 한 사건이다.
    */
    @Test
    @DisplayName("사건은 한 줄로 남길 수 있다")
    void eventsCanBeRecordedAsOneLine() {
        service.recordEvent(USER, "LOA", "휴직(결재 승인)", null, "육아휴직 2026-04-01 ~ 2026-09-30",
                "LOA", actor());

        UserChangeLog log = captured(1).get(0);
        assertThat(log.getFieldLabel()).isEqualTo("휴직(결재 승인)");
        assertThat(log.getNewValue()).contains("육아휴직");
    }

    /* ── 스냅샷 ──────────────────────────────────────── */

    /*
      ★ 매퍼가 돌려준 객체를 그대로 들고 있으면, 아래에서 같은 인스턴스를 고치므로
        비교 시점에는 앞뒤가 같아져 <이력이 통째로 안 남는다>.
    */
    @Test
    @DisplayName("스냅샷은 원본을 고쳐도 영향을 받지 않는다")
    void snapshotIsDetachedFromTheOriginal() {
        UserAccount live = user();
        UserAccount snapshot = UserChangeLogService.snapshot(live);

        live.setJobTitle("팀장");

        assertThat(snapshot.getJobTitle()).isEqualTo("사원");
        assertThat(service.record(snapshot, live, "ADMIN", actor())).isEqualTo(1);
    }

    @Test
    @DisplayName("한쪽이 없으면 남기지 않는다")
    void missingSideIsIgnored() {
        assertThat(service.record(null, user(), "ADMIN", actor())).isZero();
        assertThat(service.record(before, null, "ADMIN", actor())).isZero();
        assertThat(UserChangeLogService.snapshot(null)).isNull();
    }

    /* ── 도우미 ──────────────────────────────────────── */

    private List<UserChangeLog> captured(int times) {
        ArgumentCaptor<UserChangeLog> captor = ArgumentCaptor.forClass(UserChangeLog.class);
        verify(mapper, org.mockito.Mockito.times(times)).insert(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    private UserAccount user() {
        UserAccount u = new UserAccount();
        u.setId(USER);
        u.setName("김직원");
        u.setEmployeeNumber("2015001");
        u.setTeamId(1L);
        u.setTeamName("운영지원팀");
        u.setGradeId(5L);
        u.setGradeName("3급");
        u.setJobTitle("사원");
        u.setDept(null);
        u.setMobileNumber("010-1234-5678");
        u.setEmail("kim@example.com");
        u.setHireDate(LocalDate.of(2015, 1, 1));
        u.setActive(true);
        u.setContractor(false);
        return u;
    }

    private CustomUserDetails actor() {
        UserAccount admin = new UserAccount();
        admin.setId(99L);
        admin.setUsername("admin");
        admin.setName("관리자");
        admin.setRoles(List.of("HR_ADMIN"));
        return new CustomUserDetails(admin);
    }
}
