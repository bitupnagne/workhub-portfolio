package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.worksystem.dto.LeaveRequestForm;
import com.portfolio.worksystem.mapper.*;
import com.portfolio.worksystem.model.*;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HrLifecycleRegressionTest {
    private final HrMapper mapper = mock(HrMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final HrService service = new HrService(mapper, mock(TripReportMapper.class), users,
            approvals, mock(CommunicationService.class), mock(PanelRefreshPublisher.class),
            mock(HrScopeService.class), mock(ApprovalDocumentService.class), mock(LeaveAllowanceService.class), mock(LoaService.class));

    @Test
    void withdrawalRestoresActualDeductionAfterAnnualSettingWasDisabled() {
        var request = leave(LeaveStatus.PENDING, "1.50");
        when(mapper.findLeaveRequest(10L)).thenReturn(request);
        service.cancelLeave(user(), 10L);
        verify(mapper).increaseUsedLeave(7L, 2025, new BigDecimal("-1.50"));
        verify(mapper, never()).findLeaveTypeDef(any());
    }

    @Test
    void rejectionRestoresSnapshotRatherThanCurrentTypeWeight() {
        when(mapper.findLeaveRequest(10L)).thenReturn(leave(LeaveStatus.PENDING, "0.50"));
        service.rejectLeave(user(), 10L, "반려");
        verify(mapper).increaseUsedLeave(7L, 2025, new BigDecimal("-0.50"));
        verify(mapper, never()).findLeaveTypeDef(any());
    }

    @Test
    void cancellationOfOriginallyFreeLeaveDoesNotCreditAnnualBalance() {
        when(mapper.findLeaveRequest(10L)).thenReturn(leave(LeaveStatus.CANCEL_PENDING, "0"));
        service.approveLeaveCancel(user(), 10L);
        verify(mapper, never()).increaseUsedLeave(any(), any(), any());
        verify(mapper).updateLeaveStatus(10L, LeaveStatus.CANCEL_APPROVED, 7L, null);
    }

    @Test
    void cancellationApprovalRestoresDeductedSnapshot() {
        when(mapper.findLeaveRequest(10L)).thenReturn(leave(LeaveStatus.CANCEL_PENDING, "2"));
        service.approveLeaveCancel(user(), 10L);
        verify(mapper).increaseUsedLeave(7L, 2025, new BigDecimal("-2"));
    }

    @Test
    void cancellationCannotApproveWhenIntermediateMonthWasLocked() {
        var request = leave(LeaveStatus.CANCEL_PENDING, "2");
        request.setStartDate(LocalDate.of(2025, 1, 30));
        request.setEndDate(LocalDate.of(2025, 3, 3));
        when(mapper.findLeaveRequest(10L)).thenReturn(request);
        var locked = new AttendanceMonthLockEvent();
        locked.setAction("LOCK");
        when(mapper.findLatestMonthLockEvent("2025-02")).thenReturn(locked);
        assertThatThrownBy(() -> service.approveLeaveCancel(user(), 10L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("마감");
        verifyNoInteractions(approvals);
        verify(mapper, never()).increaseUsedLeave(any(), any(), any());
    }

    @Test
    void cancellationCannotStartInLockedMonth() {
        when(mapper.findLeaveRequest(10L)).thenReturn(leave(LeaveStatus.APPROVED, "2"));
        var locked = new AttendanceMonthLockEvent();
        locked.setAction("LOCK");
        when(mapper.findLatestMonthLockEvent("2025-03")).thenReturn(locked);
        assertThatThrownBy(() -> service.requestLeaveCancel(user(), 10L, "취소"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("마감");
        verifyNoInteractions(approvals);
    }

    @Test
    void pendingCancellationStillCountsInLeaveUsage() {
        when(mapper.findLeaveRequests(7L, null, 2025)).thenReturn(List.of(leave(LeaveStatus.CANCEL_PENDING, "2")));
        assertThat(service.leaveUsageByType(7L, 2025).get("ANNUAL")).isEqualByComparingTo("2");
    }

    @Test
    void saturdayOverrideAllowsPreviewAndActualLeaveRequest() {
        LocalDate saturday = LocalDate.of(2025, 3, 8);
        givenLeaveType(false);
        var override = new AttendanceDayOverride();
        override.setWorkDate(saturday);
        override.setStartTime(LocalTime.of(9, 0));
        override.setEndTime(LocalTime.of(18, 0));
        when(mapper.findAttendanceOverrides(saturday, saturday)).thenReturn(List.of(override));
        var step = new ApprovalStep();
        step.setStepOrder(1);
        step.setApproverUserId(99L);
        when(approvals.previewRoute(any(), any(), any(), any())).thenReturn(List.of(step));
        when(approvals.createSteps(any(), any(), any(), any(), any())).thenReturn(List.of(step));
        assertThat(service.leaveDaysPreview(user(), "ANNUAL", saturday, saturday).days()).isEqualByComparingTo("1");
        service.requestLeave(user(), new LeaveRequestForm("ANNUAL", saturday, saturday, "사유"));
        var capture = ArgumentCaptor.forClass(LeaveRequest.class);
        verify(mapper).insertLeaveRequest(capture.capture());
        assertThat(capture.getValue().getDays()).isEqualByComparingTo("1");
        assertThat(capture.getValue().getAnnualDeductedDays()).isZero();
    }

    @Test
    void fourHourOverrideCountsAsHalfDayAndHolidayStillTakesPrecedence() {
        LocalDate saturday = LocalDate.of(2025, 3, 8);
        givenLeaveType(false);
        var override = new AttendanceDayOverride();
        override.setWorkDate(saturday);
        override.setStartTime(LocalTime.of(9, 0));
        override.setEndTime(LocalTime.of(13, 0));
        when(mapper.findAttendanceOverrides(saturday, saturday)).thenReturn(List.of(override));
        assertThat(service.leaveDaysPreview(user(), "ANNUAL", saturday, saturday).days()).isEqualByComparingTo("0.5");
        var holiday = new CompanyHoliday();
        holiday.setHolidayDate(saturday);
        when(mapper.findCompanyHolidays(saturday, saturday)).thenReturn(List.of(holiday));
        assertThat(service.leaveDaysPreview(user(), "ANNUAL", saturday, saturday).days()).isZero();
    }

    @Test
    void annualRequestPersistsActualDeductedAmount() {
        givenLeaveType(true);
        var balance = new LeaveBalance();
        balance.setUserId(7L);
        balance.setTotalDays(new BigDecimal("15"));
        balance.setUsedDays(BigDecimal.ZERO);
        balance.setAdjustedDays(BigDecimal.ZERO);
        when(mapper.findLeaveBalance(7L, 2025)).thenReturn(balance);
        var step = new ApprovalStep();
        step.setStepOrder(1);
        step.setApproverUserId(99L);
        when(approvals.previewRoute(any(), any(), any(), any())).thenReturn(List.of(step));
        when(approvals.createSteps(any(), any(), any(), any(), any())).thenReturn(List.of(step));
        LocalDate monday = LocalDate.of(2025, 3, 3);
        service.requestLeave(user(), new LeaveRequestForm("ANNUAL", monday, monday, "사유"));
        var capture = ArgumentCaptor.forClass(LeaveRequest.class);
        verify(mapper).insertLeaveRequest(capture.capture());
        assertThat(capture.getValue().getAnnualDeductedDays()).isEqualByComparingTo("1");
        verify(mapper).increaseUsedLeave(eq(7L), eq(2025), eq(capture.getValue().getAnnualDeductedDays()));
    }

    private void givenLeaveType(boolean annual) {
        var type = new LeaveTypeDef();
        type.setCode("ANNUAL");
        type.setLabel("연차");
        type.setDayWeight(BigDecimal.ONE);
        type.setDeductAnnual(annual);
        type.setActive(true);
        when(mapper.findLeaveTypeDef("ANNUAL")).thenReturn(type);
    }

    private LeaveRequest leave(LeaveStatus status, String deducted) {
        var request = new LeaveRequest();
        request.setId(10L);
        request.setUserId(7L);
        request.setType("ANNUAL");
        request.setStartDate(LocalDate.of(2025, 3, 3));
        request.setEndDate(LocalDate.of(2025, 3, 4));
        request.setDays(new BigDecimal("2"));
        request.setAnnualDeductedDays(new BigDecimal(deducted));
        request.setStatus(status);
        return request;
    }

    private CustomUserDetails user() {
        var account = new UserAccount();
        account.setId(7L);
        account.setName("김직원");
        account.setRoles(List.of("USER"));
        return new CustomUserDetails(account);
    }
}
