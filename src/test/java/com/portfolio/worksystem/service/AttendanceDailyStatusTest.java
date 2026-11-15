package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.TripReportMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.AttendanceDailyStatus;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.AttendanceStatus;
import com.portfolio.worksystem.model.BusinessTrip;
import com.portfolio.worksystem.model.CompanyHoliday;
import com.portfolio.worksystem.model.LeaveRequest;
import com.portfolio.worksystem.model.UserAccount;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 일별 근태 판정의 <b>우선순위</b>를 고정한다.
 *
 * <p>하루의 상태는 여섯 가지 자료를 겹쳐 정해진다 — 휴직 · 전사 공휴일 · 승인된 휴가 ·
 * 승인된 출장 · 출퇴근 기록 · 날짜(과거/미래). 순서가 한 칸만 밀려도 결과가 뒤집힌다.
 * 예를 들어 출장을 휴가보다 먼저 보면 <b>연차 쓴 날이 출장으로 기록</b>되고,
 * 휴가를 출퇴근 기록보다 뒤에 보면 <b>연차 쓴 사람이 결근</b>이 된다.
 *
 * <p>근태는 급여·인사에 직결되는데 이 판정에 테스트가 없었다. 우선순위를 코드가 아니라
 * 이 파일이 설명하도록 남긴다.
 *
 * <p>근무조(A/B/C)는 상태를 정하지 않는다 — 유연근무 승인으로 그날의 <b>기준 출퇴근 시각</b>을
 * 바꾸고, 그 기준선이 지각·조퇴 판정에 쓰인다. 근태정정도 상태를 덮지 않고 별도 필드로 붙는다
 * (최종 승인 시 attendance_records 자체가 갱신되어 그 뒤로 반영된다).
 */
class AttendanceDailyStatusTest {

    private static final LocalDate MON = LocalDate.of(2026, 3, 2);   // 월요일, 과거
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
        when(userMapper.findAllActive()).thenReturn(List.of(employee()));
        // 기본값: 아무 자료도 없는 평일
        when(hrMapper.findWeekSchedule()).thenReturn(List.of());
        when(hrMapper.findAttendanceSetting()).thenReturn(null);   // 서비스가 09:00~18:00 폴백
        when(hrMapper.findAttendanceRecords(any(), any(), any(), any())).thenReturn(List.of());
        when(hrMapper.findAttendanceOverrides(any(), any())).thenReturn(List.of());
        when(hrMapper.findCompanyHolidays(any(), any())).thenReturn(List.of());
        when(hrMapper.findApprovedBusinessTrips(any(), any(), any(), any())).thenReturn(List.of());
        when(hrMapper.findApprovedFlexTimes(any(), any(), any(), any())).thenReturn(List.of());
        when(hrMapper.findApprovedLeavesInRange(any(), any())).thenReturn(List.of());
        when(hrMapper.findAttendanceCorrections(any(), any(), anyInt())).thenReturn(List.of());
    }

    private AttendanceDailyStatus statusOfMonday() {
        List<AttendanceDailyStatus> all = hrService.allAttendanceDailyStatuses(MON, MON);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    /* ── 기본 ─────────────────────────────────────────── */

    @Test
    @DisplayName("과거 평일에 아무 자료도 없으면 결근")
    void pastWorkdayWithNothingIsAbsent() {
        assertThat(statusOfMonday().getStatus()).isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    @DisplayName("출퇴근 기록이 있으면 그 기록의 상태를 쓴다")
    void attendanceRecordWins() {
        when(hrMapper.findAttendanceRecords(any(), any(), any(), any()))
                .thenReturn(List.of(record(AttendanceStatus.LATE)));

        assertThat(statusOfMonday().getStatus()).isEqualTo(AttendanceStatus.LATE);
    }

    /* ── 우선순위 ─────────────────────────────────────── */

    /*
      연차 쓴 날 출근 기록이 없다고 결근으로 잡히면 안 된다.
      휴가가 출퇴근 기록보다 <먼저> 판정된다는 뜻이기도 하다.
    */
    @Test
    @DisplayName("승인된 종일 휴가는 결근이 아니라 휴무로 표시된다")
    void fullDayLeaveIsNotAbsence() {
        when(hrMapper.findApprovedLeavesInRange(any(), any()))
                .thenReturn(List.of(leave("ANNUAL", "연차", MON, MON)));

        AttendanceDailyStatus s = statusOfMonday();
        assertThat(s.getStatus()).isEqualTo(AttendanceStatus.HOLIDAY);
        assertThat(s.getNote()).isEqualTo("연차");
    }

    @Test
    @DisplayName("승인된 출장은 결근이 아니라 출장으로 표시된다")
    void approvedTripIsNotAbsence() {
        when(hrMapper.findApprovedBusinessTrips(any(), any(), any(), any()))
                .thenReturn(List.of(trip("부산", MON, MON)));

        AttendanceDailyStatus s = statusOfMonday();
        assertThat(s.getStatus()).isEqualTo(AttendanceStatus.BUSINESS_TRIP);
        assertThat(s.getTripDestination()).isEqualTo("부산");
    }

    /* 휴가가 출장보다 앞선다 — 뒤집히면 연차 쓴 날이 출장으로 남는다. */
    @Test
    @DisplayName("같은 날 휴가와 출장이 겹치면 휴가가 이긴다")
    void leaveOutranksTrip() {
        when(hrMapper.findApprovedLeavesInRange(any(), any()))
                .thenReturn(List.of(leave("ANNUAL", "연차", MON, MON)));
        when(hrMapper.findApprovedBusinessTrips(any(), any(), any(), any()))
                .thenReturn(List.of(trip("부산", MON, MON)));

        assertThat(statusOfMonday().getStatus()).isEqualTo(AttendanceStatus.HOLIDAY);
    }

    /* 공휴일이 휴가보다 앞선다 — 공휴일에 연차가 소진된 것처럼 보이면 안 된다. */
    @Test
    @DisplayName("전사 공휴일이 휴가보다 앞선다")
    void companyHolidayOutranksLeave() {
        when(hrMapper.findCompanyHolidays(any(), any())).thenReturn(List.of(holiday("창립기념일")));
        when(hrMapper.findApprovedLeavesInRange(any(), any()))
                .thenReturn(List.of(leave("ANNUAL", "연차", MON, MON)));

        AttendanceDailyStatus s = statusOfMonday();
        assertThat(s.getStatus()).isEqualTo(AttendanceStatus.HOLIDAY);
        assertThat(s.getHolidayName()).isEqualTo("창립기념일");
    }

    /* 휴직이 최우선 — 휴직 중에는 어떤 자료가 있어도 결근 판정을 하지 않는다. */
    @Test
    @DisplayName("휴직이 모든 것보다 앞선다")
    void leaveOfAbsenceOutranksEverything() {
        UserAccount onLoa = employee();
        onLoa.setLoaStart(MON.minusMonths(1));
        onLoa.setLoaEnd(MON.plusMonths(1));
        when(userMapper.findAllActive()).thenReturn(List.of(onLoa));
        when(hrMapper.findCompanyHolidays(any(), any())).thenReturn(List.of(holiday("창립기념일")));

        AttendanceDailyStatus s = statusOfMonday();
        assertThat(s.getStatus()).isEqualTo(AttendanceStatus.HOLIDAY);
        assertThat(s.getNote()).isEqualTo("휴직");
    }

    /* ── 반차 ─────────────────────────────────────────── */

    /*
      반차는 종일 부재가 아니므로 상태를 덮지 않는다. 출근 기록이 있으면 그 상태를 쓰고
      비고에 반차 종류를 병기한다.
    */
    @Test
    @DisplayName("반차는 상태를 덮지 않고 비고에 병기된다")
    void halfDayLeaveAnnotatesInsteadOfOverriding() {
        when(hrMapper.findApprovedLeavesInRange(any(), any()))
                .thenReturn(List.of(leave("AM_HALF", "오전반차", MON, MON)));
        when(hrMapper.findAttendanceRecords(any(), any(), any(), any()))
                .thenReturn(List.of(record(AttendanceStatus.NORMAL)));

        AttendanceDailyStatus s = statusOfMonday();
        assertThat(s.getStatus()).isEqualTo(AttendanceStatus.NORMAL);
        assertThat(s.getNote()).contains("오전반차");
    }

    /*
      ★ 오전반차 + 오후반차를 따로 신청해 둘 다 승인된 경우.
        휴가 겹침 검증은 예외가 아니라 <경고> 라서 이 상태가 실제로 만들어질 수 있다
        — 오전은 연차, 오후는 보건휴가처럼 종류가 다르면 특히 그렇다.

        예전에는 그날 휴가를 하나만 골라 종일 여부를 봤기 때문에 어느 쪽도 종일이 아니라서
        하루를 다 쉬고도 결근으로 잡혔다. 이제는 오전·오후가 함께 있으면 종일로 친다.
    */
    @Test
    @DisplayName("오전+오후 반차를 따로 신청해도 종일 휴무로 인정된다")
    void amPlusPmHalfDayLeavesCoverTheWholeDay() {
        when(hrMapper.findApprovedLeavesInRange(any(), any())).thenReturn(List.of(
                leave("AM_HALF", "오전반차", MON, MON),
                leave("PM_HALF", "오후반차", MON, MON)));

        AttendanceDailyStatus s = statusOfMonday();

        // 출근 기록이 없는 과거지만 결근이 아니라 휴무
        assertThat(s.getStatus()).isEqualTo(AttendanceStatus.HOLIDAY);
        // 두 반차 종류를 병기한다
        assertThat(s.getNote()).contains("오전반차").contains("오후반차");
    }

    @Test
    @DisplayName("오전 반차 하나만 있으면 종일로 인정되지 않는다")
    void aLoneHalfDayLeaveDoesNotCoverTheWholeDay() {
        when(hrMapper.findApprovedLeavesInRange(any(), any())).thenReturn(List.of(
                leave("AM_HALF", "오전반차", MON, MON)));

        // 나머지 반일은 근무해야 하므로, 출근 기록이 없는 과거는 여전히 결근
        assertThat(statusOfMonday().getStatus()).isEqualTo(AttendanceStatus.ABSENT);
    }

    /* ── 요일·기간 ────────────────────────────────────── */

    @Test
    @DisplayName("주말은 목록에 나오지 않는다")
    void weekendsAreOmitted() {
        LocalDate saturday = LocalDate.of(2026, 3, 7);

        assertThat(hrService.allAttendanceDailyStatuses(saturday, saturday)).isEmpty();
    }

    @Test
    @DisplayName("미래 근무일은 결근이 아니라 예정")
    void futureWorkdayIsScheduled() {
        LocalDate future = LocalDate.now().plusDays(10);
        while (future.getDayOfWeek().getValue() >= 6) {
            future = future.plusDays(1);
        }

        List<AttendanceDailyStatus> all = hrService.allAttendanceDailyStatuses(future, future);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);
    }

    @Test
    @DisplayName("여러 날 걸친 휴가는 기간 안의 모든 근무일에 적용된다")
    void multiDayLeaveCoversEveryWorkdayInRange() {
        LocalDate tue = MON.plusDays(1);
        when(hrMapper.findApprovedLeavesInRange(any(), any()))
                .thenReturn(List.of(leave("ANNUAL", "연차", MON, tue)));

        List<AttendanceDailyStatus> all = hrService.allAttendanceDailyStatuses(MON, tue);
        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(s ->
                assertThat(s.getStatus()).isEqualTo(AttendanceStatus.HOLIDAY));
    }

    /* ── 기준 근무시간 ────────────────────────────────── */

    @Test
    @DisplayName("설정이 없으면 09:00~18:00 을 기준 근무시간으로 쓴다")
    void fallsBackToNineToSixWhenUnset() {
        AttendanceDailyStatus s = statusOfMonday();

        assertThat(s.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(s.getEndTime()).isEqualTo(LocalTime.of(18, 0));
    }

    /* ── 픽스처 ───────────────────────────────────────── */

    private UserAccount employee() {
        UserAccount u = new UserAccount();
        u.setId(USER);
        u.setName("직원");
        u.setActive(true);
        u.setRoles(List.of("EMPLOYEE"));
        return u;
    }

    private AttendanceRecord record(AttendanceStatus status) {
        AttendanceRecord r = new AttendanceRecord();
        r.setUserId(USER);
        r.setWorkDate(MON);
        r.setStatus(status);
        r.setCheckInAt(LocalDateTime.of(MON, LocalTime.of(9, 30)));
        r.setCheckOutAt(LocalDateTime.of(MON, LocalTime.of(18, 0)));
        return r;
    }

    private LeaveRequest leave(String type, String label, LocalDate start, LocalDate end) {
        LeaveRequest l = new LeaveRequest();
        l.setUserId(USER);
        l.setType(type);
        l.setTypeLabel(label);
        l.setStartDate(start);
        l.setEndDate(end);
        return l;
    }

    private BusinessTrip trip(String destination, LocalDate start, LocalDate end) {
        BusinessTrip t = new BusinessTrip();
        t.setUserId(USER);
        t.setDestination(destination);
        t.setStartDate(start);
        t.setEndDate(end);
        return t;
    }

    private CompanyHoliday holiday(String name) {
        CompanyHoliday h = new CompanyHoliday();
        h.setHolidayDate(MON);
        h.setName(name);
        return h;
    }
}
