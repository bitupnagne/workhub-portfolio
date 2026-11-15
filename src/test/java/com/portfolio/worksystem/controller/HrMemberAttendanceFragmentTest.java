package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.worksystem.model.AttendanceDailyStatus;
import com.portfolio.worksystem.model.AttendanceStatus;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.HrService;
import com.portfolio.worksystem.service.OrganizationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class HrMemberAttendanceFragmentTest {
    private MockMvc mvc;
    private HrService hr;
    private OrganizationService organization;
    private final CustomUserDetails user = mock(CustomUserDetails.class);

    @BeforeEach
    void setup() throws Exception {
        var constructor = HrController.class.getConstructors()[0];
        Object[] services = Arrays.stream(constructor.getParameterTypes()).map(type -> mock(type)).toArray();
        hr = (HrService) services[0];
        organization = (OrganizationService) services[2];
        when(user.getUserId()).thenReturn(77L);
        when(hr.canViewMemberHrOverview(user)).thenReturn(true);
        when(hr.canViewMemberHrCard(user, 9L)).thenReturn(true);
        var target = new UserAccount();
        target.setId(9L);
        target.setName("검증 직원");
        when(hr.findUser(9L)).thenReturn(target);
        when(hr.remainingLeaveDays(eq(9L), anyInt())).thenReturn(BigDecimal.ZERO);
        List<AttendanceDailyStatus> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            var row = new AttendanceDailyStatus();
            row.setUserId(9L);
            row.setWorkDate(LocalDate.of(2026, 1, 1).plusDays(i));
            row.setStatus(AttendanceStatus.ABSENT);
            rows.add(row);
        }
        var other = new AttendanceDailyStatus();
        other.setUserId(22L);
        other.setStatus(AttendanceStatus.ABSENT);
        rows.add(other);
        when(hr.memberAttendanceDailyStatuses(eq(user), any(), any())).thenReturn(rows);
        var controller = (HrController) constructor.newInstance(services);
        mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == CustomUserDetails.class; }
            public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) { return user; }
        }).build();
    }

    @Test
    void fragmentPreservesPeriodStatusAndOtherPageContextWithoutLoadingOtherTables() throws Exception {
        var result = mvc.perform(get("/hr/members/9?from=2026-01-01&to=2026-01-25&lpage=2&fpage=3&tpage=4&apage=2&astatus=absent")
                        .header("X-Worksystem-Fragment", "member-attendance"))
                .andExpect(status().isOk()).andExpect(view().name("hr/members-detail :: memberAttendance"))
                .andExpect(header().string("Vary", "X-Worksystem-Fragment"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(model().attribute("attendanceTotal", 25))
                .andExpect(model().attribute("memberAttendanceUrl", "/hr/members/9?from=2026-01-01&to=2026-01-25&astatus=ABSENT&lpage=2&fpage=3&tpage=4&apage=2"))
                .andReturn();
        @SuppressWarnings("unchecked")
        var rows = (List<AttendanceDailyStatus>) result.getModelAndView().getModel().get("attendanceList");
        assertThat(rows).hasSize(10).allMatch(row -> row.getUserId().equals(9L));
        assertThat(rows.getFirst().getWorkDate()).isEqualTo(LocalDate.of(2026, 1, 11));
        verify(hr, never()).memberLeaveRequests(any(), anyInt());
        verify(hr, never()).memberFlexTimeRequests(any(), anyInt());
        verify(hr, never()).memberTripRequests(any(), anyInt());
        verify(hr, never()).remainingLeaveDays(anyLong(), anyInt());
        verifyNoInteractions(organization);
    }

    @Test
    void canonicalFragmentUrlUsesContextPathAndClampedAttendancePage() throws Exception {
        mvc.perform(get("/office/hr/members/9?from=2026-01-01&to=2026-01-25&apage=999")
                        .contextPath("/office").header("X-Worksystem-Fragment", "member-attendance"))
                .andExpect(view().name("hr/members-detail :: memberAttendance"))
                .andExpect(model().attribute("memberAttendanceUrl", "/office/hr/members/9?from=2026-01-01&to=2026-01-25&astatus=&lpage=1&fpage=1&tpage=1&apage=3"));
    }

    @Test
    void unknownFragmentHeaderKeepsTheNormalCardView() throws Exception {
        mvc.perform(get("/hr/members/9?from=2026-01-01&to=2026-01-25")
                        .header("X-Worksystem-Fragment", "admin/users"))
                .andExpect(view().name("hr/members-detail"))
                .andExpect(model().attributeExists("leaveList", "changeLogs", "memberAttendanceUrl"));
    }

    @Test
    void fragmentCannotBypassTargetScopeAndSelfStillUsesTheExistingRedirect() throws Exception {
        when(hr.canViewMemberHrCard(user, 9L)).thenReturn(false);
        assertThatThrownBy(() -> mvc.perform(get("/hr/members/9").header("X-Worksystem-Fragment", "member-attendance")))
                .hasCauseInstanceOf(BusinessException.class);
        verify(hr, never()).findUser(anyLong());
        verify(hr, never()).memberAttendanceDailyStatuses(any(), any(), any());
        when(hr.canViewMemberHrOverview(user)).thenReturn(false);
        mvc.perform(get("/hr/members/77").header("X-Worksystem-Fragment", "member-attendance"))
                .andExpect(redirectedUrl("/mypage"));
    }
}
