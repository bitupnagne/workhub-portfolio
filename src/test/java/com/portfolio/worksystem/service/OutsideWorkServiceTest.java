package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.OutsideWorkMapper;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.AttendanceStatus;
import com.portfolio.worksystem.model.OutsideWorkRequest;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** [OW] 외근 — 정해진 출근·퇴근 시각에 걸칠 때만 출퇴근 유예, 중간 외근은 근태 변화 없음. */
class OutsideWorkServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 25);

    private final OutsideWorkMapper mapper = mock(OutsideWorkMapper.class);
    private final HrMapper hrMapper = mock(HrMapper.class);
    private final HrService hrService = mock(HrService.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final OutsideWorkService service = new OutsideWorkService(mapper, hrMapper, hrService, approvalService,
            mock(HrScopeService.class));

    // 정해진 근무시간 09:00~18:00
    private void schedule() {
        when(hrService.workSchedule(eq(1L), eq(DAY)))
                .thenReturn(new HrService.WorkSchedule(LocalTime.of(9, 0), LocalTime.of(18, 0), null, false));
    }

    private void approved(int outH, int outM, int endH, int endM) {
        OutsideWorkRequest r = request();
        r.setOutTime(LocalTime.of(outH, outM));
        r.setEndTime(LocalTime.of(endH, endM));
        when(mapper.findApprovedOnDate(1L, DAY)).thenReturn(r);
    }

    private AttendanceRecord record(LocalTime in, LocalTime out, AttendanceStatus status) {
        AttendanceRecord record = new AttendanceRecord();
        record.setId(5L);
        record.setCheckInAt(in == null ? null : DAY.atTime(in));
        record.setCheckOutAt(out == null ? null : DAY.atTime(out));
        record.setStatus(status);
        when(hrMapper.findAttendanceByUserDate(1L, DAY)).thenReturn(record);
        return record;
    }

    private static final LocalDateTime NIGHT = DAY.atTime(23, 0);

    @Test
    void middleOfDayOutsideWorkChangesNothing() {
        schedule();
        approved(13, 0, 17, 30);
        record(LocalTime.of(9, 0), null, AttendanceStatus.NORMAL);

        service.reconcile(1L, DAY, NIGHT);

        verify(hrMapper, never()).updateAttendanceRecord(anyLong(), any(), any(), any());
        verify(hrMapper, never()).insertAttendance(any());
        verify(mapper, never()).markAttendanceApplied(anyLong());
    }

    @Test
    void outsideUntilEndTimeExcusesCheckoutAsScheduledEnd() {
        schedule();
        approved(15, 0, 18, 0);
        record(LocalTime.of(8, 55), null, AttendanceStatus.NORMAL);

        service.reconcile(1L, DAY, NIGHT);

        verify(hrMapper).updateAttendanceRecord(5L, DAY.atTime(8, 55), DAY.atTime(18, 0), AttendanceStatus.OUTSIDE);
        verify(mapper).markAttendanceApplied(9L);
    }

    @Test
    void earlyCheckoutBeforeGoingOutIsNotEarlyLeave() {
        schedule();
        approved(15, 0, 18, 0);
        record(LocalTime.of(9, 0), LocalTime.of(15, 0), AttendanceStatus.EARLY_LEAVE);

        service.reconcile(1L, DAY, DAY.atTime(15, 1));

        verify(hrMapper).updateAttendanceRecord(5L, DAY.atTime(9, 0), DAY.atTime(18, 0), AttendanceStatus.OUTSIDE);
    }

    @Test
    void checkoutIsNotClosedEarlyWhileStillWorking() {
        schedule();
        approved(15, 0, 18, 0);
        record(LocalTime.of(9, 0), null, AttendanceStatus.NORMAL);

        service.reconcile(1L, DAY, DAY.atTime(11, 0));

        verify(hrMapper, never()).updateAttendanceRecord(anyLong(), any(), any(), any());
    }

    @Test
    void outsideFromStartTimeExcusesLateCheckIn() {
        schedule();
        approved(9, 0, 13, 0);
        record(LocalTime.of(13, 40), null, AttendanceStatus.LATE);

        service.reconcile(1L, DAY, DAY.atTime(13, 40));

        verify(hrMapper).updateAttendanceRecord(5L, DAY.atTime(9, 0), null, AttendanceStatus.OUTSIDE);
    }

    @Test
    void lateArrivalStaysLateWhenOnlyCheckoutIsExcused() {
        schedule();
        approved(16, 0, 18, 0);
        record(LocalTime.of(9, 30), null, AttendanceStatus.LATE);

        service.reconcile(1L, DAY, NIGHT);

        verify(hrMapper).updateAttendanceRecord(5L, DAY.atTime(9, 30), DAY.atTime(18, 0), AttendanceStatus.LATE);
    }

    @Test
    void wholeDayOutsideWithoutRecordCreatesScheduledHours() {
        schedule();
        approved(9, 0, 18, 0);

        service.reconcile(1L, DAY, NIGHT);

        ArgumentCaptor<AttendanceRecord> saved = ArgumentCaptor.forClass(AttendanceRecord.class);
        verify(hrMapper).insertAttendance(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AttendanceStatus.OUTSIDE);
        assertThat(saved.getValue().getCheckInAt()).isEqualTo(DAY.atTime(9, 0));
        assertThat(saved.getValue().getCheckOutAt()).isEqualTo(DAY.atTime(18, 0));
    }

    @Test
    void checkoutOnlyOutsideWithoutCheckInCreatesNothing() {
        schedule();
        approved(15, 0, 18, 0);

        service.reconcile(1L, DAY, NIGHT);

        verify(hrMapper, never()).insertAttendance(any());
    }

    @Test
    void effectLabelNamesWhatIsExcused() {
        schedule();
        OutsideWorkRequest r = request();
        r.setOutTime(LocalTime.of(13, 0));
        r.setEndTime(LocalTime.of(17, 30));
        assertThat(service.effectLabel(r)).isEqualTo("근태 변화 없음");
        r.setEndTime(LocalTime.of(18, 0));
        assertThat(service.effectLabel(r)).isEqualTo("퇴근 유예");
        r.setOutTime(LocalTime.of(9, 0));
        assertThat(service.effectLabel(r)).isEqualTo("출근·퇴근 유예");
        r.setEndTime(LocalTime.of(12, 0));
        assertThat(service.effectLabel(r)).isEqualTo("출근 유예");
        // 출근 전에 끝나거나 퇴근 뒤에 시작하면 출퇴근 시각을 포함하지 않는다
        r.setOutTime(LocalTime.of(0, 30));
        r.setEndTime(LocalTime.of(8, 0));
        assertThat(service.effectLabel(r)).isEqualTo("근태 변화 없음");
        r.setOutTime(LocalTime.of(19, 0));
        r.setEndTime(LocalTime.of(21, 0));
        assertThat(service.effectLabel(r)).isEqualTo("근태 변화 없음");
        // 휴무일 외근은 근태와 무관
        r.setOutTime(LocalTime.of(9, 0));
        r.setEndTime(LocalTime.of(18, 0));
        when(hrService.isOffDay(DAY)).thenReturn(true);
        assertThat(service.effectLabel(r)).isEqualTo("근태 변화 없음");
    }

    @Test
    void outsideWorkThatTouchesNeitherStartNorEndIsApprovedWithoutApproval() {
        todaySchedule();
        OutsideWorkRequest saved = service.submit(user(), LocalDate.now(), LocalTime.of(13, 0), LocalTime.of(17, 30), "고객사", null, null);

        assertThat(saved.getStatus()).isEqualTo(com.portfolio.worksystem.model.OvertimeStatus.APPROVED);
        verify(approvalService, never()).createSteps(any(), any(), any(), any(), any());
        verify(mapper).updateStatus(any(), eq("APPROVED"), eq(1L), any());
    }

    @Test
    void outsideWorkUntilEndTimeGoesThroughApproval() {
        todaySchedule();
        com.portfolio.worksystem.model.ApprovalStep step = new com.portfolio.worksystem.model.ApprovalStep();
        step.setStepOrder(1);
        when(approvalService.createSteps(any(), any(), any(), any(), any())).thenReturn(List.of(step));

        OutsideWorkRequest saved = service.submit(user(), LocalDate.now(), LocalTime.of(15, 0), LocalTime.of(18, 0), "고객사", null, null);

        assertThat(saved.getStatus()).isEqualTo(com.portfolio.worksystem.model.OvertimeStatus.PENDING);
        verify(approvalService).createSteps(any(), any(), any(), any(), any());
    }

    private void todaySchedule() {
        when(hrService.workSchedule(eq(1L), any()))
                .thenReturn(new HrService.WorkSchedule(LocalTime.of(9, 0), LocalTime.of(18, 0), null, false));
    }

    @Test
    void submitValidatesTimesAndBlockedDays() {
        CustomUserDetails user = user();
        assertThatThrownBy(() -> service.submit(user, DAY, LocalTime.of(17, 0), LocalTime.of(16, 0), "고객사", null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("늦어야");
        when(hrService.todayCheckBlockReasonOn(eq(1L), any())).thenReturn("승인된 출장일은 출장으로 처리됩니다.");
        assertThatThrownBy(() -> service.submit(user, LocalDate.now(), LocalTime.of(13, 0), LocalTime.of(17, 0), "고객사", null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("출장");
        verify(mapper, never()).insert(any());
    }

    private static OutsideWorkRequest request() {
        OutsideWorkRequest r = new OutsideWorkRequest();
        r.setId(9L);
        r.setUserId(1L);
        r.setWorkDate(DAY);
        r.setOutTime(LocalTime.of(13, 0));
        r.setEndTime(LocalTime.of(17, 30));
        r.setPlace("고객사");
        return r;
    }

    private static CustomUserDetails user() {
        UserAccount account = new UserAccount();
        account.setId(1L);
        account.setUsername("u1");
        account.setName("직원");
        account.setActive(true);
        account.setRoles(List.of("EMPLOYEE"));
        return new CustomUserDetails(account);
    }
}
