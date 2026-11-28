package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.OvertimeMapper;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.OvertimeRequest;
import com.portfolio.worksystem.model.OvertimeSession;
import com.portfolio.worksystem.model.OvertimeSetting;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** [OT] 신청하지 않은 시간외 근무 찾기 — 기록은 있는데 신청서가 없는 구간. */
class OvertimeNoticeServiceTest {

    private static final long USER = 7L;

    private OvertimeMapper overtimeMapper;
    private HrMapper hrMapper;
    private HrService hrService;
    private OvertimeNoticeService service;
    private LocalDate day;

    @BeforeEach
    void setUp() {
        overtimeMapper = mock(OvertimeMapper.class);
        hrMapper = mock(HrMapper.class);
        hrService = mock(HrService.class);
        service = new OvertimeNoticeService(overtimeMapper, hrMapper, hrService);
        // 이번 달만 보므로 오늘 날짜를 근무일로 쓴다.
        day = LocalDate.now(ZoneId.of("Asia/Seoul"));
        OvertimeSetting setting = new OvertimeSetting();
        setting.setNoticeThresholdMinutes(30);
        when(overtimeMapper.findSetting()).thenReturn(setting);
        when(overtimeMapper.findSessions(eq(USER), any(), any())).thenReturn(List.of());
        when(overtimeMapper.findActiveRequests(eq(USER), any(), any())).thenReturn(List.of());
        when(hrMapper.findAttendanceRecords(eq(USER), isNull(), any(), any())).thenReturn(List.of());
        when(hrService.workSchedule(eq(USER), any()))
                .thenReturn(new HrService.WorkSchedule(LocalTime.of(9, 0), LocalTime.of(18, 0), "기본", false));
    }

    private AttendanceRecord record(int inHour, int inMinute, int outHour, int outMinute) {
        AttendanceRecord record = new AttendanceRecord();
        record.setUserId(USER);
        record.setWorkDate(day);
        record.setCheckInAt(LocalDateTime.of(day, LocalTime.of(inHour, inMinute)));
        record.setCheckOutAt(LocalDateTime.of(day, LocalTime.of(outHour, outMinute)));
        return record;
    }

    @Test
    void lateCheckoutBeyondThresholdIsACandidate() {
        when(hrMapper.findAttendanceRecords(eq(USER), isNull(), any(), any())).thenReturn(List.of(record(9, 0, 20, 40)));

        List<OvertimeNoticeService.Candidate> found = service.unrequested(USER);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).kindLabel()).isEqualTo("퇴근시각 이후");
        assertThat(found.get(0).getTimeText()).isEqualTo("18:00 ~ 20:40");
        assertThat(found.get(0).getMinutes()).isEqualTo(160);
    }

    @Test
    void leavingAFewMinutesLateIsNotAnnounced() {
        when(hrMapper.findAttendanceRecords(eq(USER), isNull(), any(), any())).thenReturn(List.of(record(8, 45, 18, 20)));

        assertThat(service.unrequested(USER)).isEmpty();
    }

    @Test
    void anOverlappingRequestRemovesTheCandidate() {
        when(hrMapper.findAttendanceRecords(eq(USER), isNull(), any(), any())).thenReturn(List.of(record(9, 0, 20, 40)));
        OvertimeRequest request = new OvertimeRequest();
        request.setStartAt(LocalDateTime.of(day, LocalTime.of(18, 0)));
        request.setEndAt(LocalDateTime.of(day, LocalTime.of(21, 0)));
        when(overtimeMapper.findActiveRequests(eq(USER), any(), any())).thenReturn(List.of(request));

        assertThat(service.unrequested(USER)).isEmpty();
    }

    @Test
    void earlyArrivalIsClippedToTimeActuallyPresent() {
        // 새벽에 나와 기준 출근시각 전에 퇴근까지 끝낸 날 — 09:00 까지로 늘려 잡지 않는다.
        when(hrMapper.findAttendanceRecords(eq(USER), isNull(), any(), any())).thenReturn(List.of(record(5, 0, 6, 30)));

        List<OvertimeNoticeService.Candidate> found = service.unrequested(USER);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).kindLabel()).isEqualTo("출근시각 이전");
        assertThat(found.get(0).getTimeText()).isEqualTo("05:00 ~ 06:30");
    }

    @Test
    void closedSessionsAreCandidatesButZeroLengthOnesAreNot() {
        OvertimeSession real = new OvertimeSession();
        real.setWorkDate(day);
        real.setCheckInAt(LocalDateTime.of(day, LocalTime.of(19, 30)));
        real.setCheckOutAt(LocalDateTime.of(day, LocalTime.of(21, 0)));
        OvertimeSession mistake = new OvertimeSession();
        mistake.setWorkDate(day);
        mistake.setCheckInAt(LocalDateTime.of(day, LocalTime.of(22, 0)));
        mistake.setCheckOutAt(LocalDateTime.of(day, LocalTime.of(22, 0)));
        OvertimeSession open = new OvertimeSession();
        open.setWorkDate(day);
        open.setCheckInAt(LocalDateTime.of(day, LocalTime.of(23, 0)));
        when(overtimeMapper.findSessions(eq(USER), any(), any())).thenReturn(List.of(real, mistake, open));

        List<OvertimeNoticeService.Candidate> found = service.unrequested(USER);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).kindLabel()).isEqualTo("시간외 출퇴근");
        assertThat(found.get(0).getMinutes()).isEqualTo(90);
    }

    @Test
    void countIsRememberedUntilEvicted() {
        when(hrMapper.findAttendanceRecords(eq(USER), isNull(), any(), any())).thenReturn(List.of(record(9, 0, 20, 40)));
        assertThat(service.unrequestedCount(USER)).isEqualTo(1);

        when(hrMapper.findAttendanceRecords(eq(USER), isNull(), any(), any())).thenReturn(List.of());
        assertThat(service.unrequestedCount(USER)).isEqualTo(1);

        service.evict(USER);
        assertThat(service.unrequestedCount(USER)).isZero();
    }
}
