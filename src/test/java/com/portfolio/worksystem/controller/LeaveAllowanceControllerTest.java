package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.LeaveAllowanceService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class LeaveAllowanceControllerTest {
    private final LeaveAllowanceService allowances = mock(LeaveAllowanceService.class);
    private final LeaveAllowanceController controller = new LeaveAllowanceController(allowances);
    private final CustomUserDetails user = mock(CustomUserDetails.class);

    @Test
    void savesOnlyTheSelectedGradeAndReturnsToTheSameLedgerQuery() {
        when(allowances.currentYear()).thenReturn(2026);
        when(allowances.saveRate(user, 2025, 3L, "180000")).thenReturn("저장했습니다.");
        var flash = new RedirectAttributesModelMap();

        String target = controller.saveLedgerAllowance(user, 2025, 3L, "180000", "A & B", 2, 3, flash);

        assertThat(target).isEqualTo("redirect:/hr/leave/ledger?year=2025&q=A+%26+B&page=2&apage=3");
        assertThat(flash.getFlashAttributes().get("message")).isEqualTo("저장했습니다.");
        verify(allowances).saveRate(user, 2025, 3L, "180000");
        verify(allowances, never()).saveRates(any(), anyInt(), any());
    }

    @Test
    void validationFailureRetainsLedgerContextAndShowsTheServiceError() {
        when(allowances.currentYear()).thenReturn(2026);
        when(allowances.saveRate(user, 2025, 3L, "-1")).thenThrow(new BusinessException("0원 이상이어야 합니다."));
        var flash = new RedirectAttributesModelMap();

        String target = controller.saveLedgerAllowance(user, 2025, 3L, "-1", "팀", 2, 1, flash);

        assertThat(target).startsWith("redirect:/hr/leave/ledger?year=2025&q=").endsWith("&page=2&apage=1");
        assertThat(flash.getFlashAttributes().get("error")).isEqualTo("0원 이상이어야 합니다.");
    }
}
