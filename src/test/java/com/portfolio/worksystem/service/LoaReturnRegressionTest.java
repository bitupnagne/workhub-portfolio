package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.portfolio.worksystem.mapper.LoaMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.*;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class LoaReturnRegressionTest {
    private final LoaMapper mapper = mock(LoaMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final HrScopeService scope = mock(HrScopeService.class);
    private final LoaService service = new LoaService(mapper, users, mock(ApprovalService.class), scope,
            mock(UserChangeLogService.class));
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private final UserAccount target = new UserAccount();
    private CustomUserDetails manager;

    @BeforeEach
    void setUp() {
        target.setId(7L);
        target.setName("직원");
        target.setLoaType("PERSONAL");
        target.setLoaStart(today.minusMonths(2));
        when(users.findById(7L)).thenReturn(target);
        when(scope.canManageHr(any())).thenReturn(true);
        var account = new UserAccount();
        account.setId(99L);
        account.setRoles(List.of("HR_MANAGER"));
        manager = new CustomUserDetails(account);
    }

    @Test
    void approvedOpenEndedPeriodIsClosedBeforeCurrentLeaveIsCleared() {
        when(mapper.closeApprovedPeriod(any(), any(), any(), any(), any())).thenReturn(1);
        service.setLeaveOfAbsence(manager, 7L, null, null, null);
        InOrder order = inOrder(mapper);
        order.verify(mapper).closeApprovedPeriod(7L, "PERSONAL", target.getLoaStart(), today.minusDays(1), 99L);
        order.verify(mapper).applyToUser(7L, null, null, null);
        verify(mapper, never()).insertRequest(any());
    }

    @Test
    void directlyAssignedLeaveIsArchivedAndNoLongerExtendsPastReturn() {
        service.setLeaveOfAbsence(manager, 7L, null, null, null);
        var capture = ArgumentCaptor.forClass(LoaRequest.class);
        verify(mapper).insertRequest(capture.capture());
        LoaRequest history = capture.getValue();
        assertThat(history.getStartDate()).isEqualTo(target.getLoaStart());
        assertThat(history.getEndDate()).isEqualTo(today.minusDays(1));
        assertThat(history.getStatus()).isEqualTo(LoaStatus.APPROVED);
        assertThat(history.getCurrentApprovalStep()).isEqualTo(1);
        history.setAccrualTreatment("EXCLUDED");
        int absenceYear = today.minusDays(1).getYear();
        when(mapper.findApprovedPeriodsInYear(absenceYear)).thenReturn(List.of(history));
        assertThat(service.excludedLoaDatesByUser(absenceYear).get(7L))
                .contains(today.minusDays(1)).doesNotContain(today, today.plusDays(30));
    }

    @Test
    void expiredDirectAssignmentKeepsOriginalEndDate() {
        target.setLoaEnd(today.minusDays(14));
        service.setLeaveOfAbsence(manager, 7L, null, null, null);
        var capture = ArgumentCaptor.forClass(LoaRequest.class);
        verify(mapper).insertRequest(capture.capture());
        assertThat(capture.getValue().getEndDate()).isEqualTo(target.getLoaEnd());
    }

    @Test
    void futureDirectAssignmentDoesNotCreateNegativeHistoryPeriod() {
        target.setLoaStart(today.plusDays(1));
        service.setLeaveOfAbsence(manager, 7L, null, null, null);
        verify(mapper, never()).insertRequest(any());
        verify(mapper).applyToUser(7L, null, null, null);
    }
}
