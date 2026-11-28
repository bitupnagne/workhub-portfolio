package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.HrService;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class HrAttendanceFragmentTest {
    private MockMvc mvc;
    private HrService hr;
    private final CustomUserDetails user = mock(CustomUserDetails.class);

    @BeforeEach
    void setup() throws Exception {
        var constructor = HrController.class.getConstructors()[0];
        Object[] services = Arrays.stream(constructor.getParameterTypes()).map(type -> mock(type)).toArray();
        hr = (HrService) services[0];
        when(user.getUserId()).thenReturn(77L);
        when(hr.currentYear()).thenReturn(2026);
        var controller = (HrController) constructor.newInstance(services);
        mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == CustomUserDetails.class; }
            public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) { return user; }
        }).build();
    }

    @Test
    void normalDeepLinkKeepsFullPageAndItsCalendarFilterContext() throws Exception {
        mvc.perform(get("/hr/attendance?tab=leave&cal=2026-09&lq=meeting"))
                .andExpect(status().isOk()).andExpect(view().name("hr/attendance"))
                .andExpect(model().attribute("hrTab", "leave"))
                .andExpect(model().attribute("cal", "2026-09"))
                .andExpect(model().attribute("attendanceHistoryUrl", "/hr/attendance?tab=leave&cal=2026-09&lq=meeting"));
        verify(hr, atLeastOnce()).ownLeaveRequests(eq(77L), anyInt());
    }

    @Test
    void allFiveFragmentRequestsUseTheSameUserScopedHandlerAndModel() throws Exception {
        for (String tab : java.util.List.of("attendance", "leave", "flex", "trip", "correction")) {
            mvc.perform(get("/hr/attendance?tab=" + tab).header("X-Worksystem-Fragment", "attendance-history"))
                    .andExpect(status().isOk()).andExpect(view().name("hr/attendance :: attendanceHistory"))
                    .andExpect(model().attribute("hrTab", tab)).andExpect(model().attribute("currentUser", user));
        }
        verify(hr, atLeastOnce()).attendanceDailyStatuses(eq(user), any(), any());
        verify(hr, atLeastOnce()).ownAttendanceCorrections(eq(77L), anyInt());
        verify(hr, atLeastOnce()).ownBusinessTrips(eq(77L), anyInt());
    }

    @Test
    void unrecognizedFragmentCannotSelectArbitraryViewsAndInvalidTabHasARealPanel() throws Exception {
        mvc.perform(get("/hr/attendance?tab=unknown").header("X-Worksystem-Fragment", "admin/users"))
                .andExpect(view().name("hr/attendance")).andExpect(model().attribute("hrTab", "attendance"));
    }

    @Test
    void fragmentDoesNotAlterRequestPeriodNormalizationOrUserIdentity() throws Exception {
        var result = mvc.perform(get("/hr/attendance?tab=trip&from=2026-10-31&to=2026-10-01")
                        .header("X-Worksystem-Fragment", "attendance-history"))
                .andExpect(view().name("hr/attendance :: attendanceHistory"))
                .andExpect(model().attribute("fromDate", java.time.LocalDate.of(2026, 10, 1)))
                .andExpect(model().attribute("toDate", java.time.LocalDate.of(2026, 10, 31)))
                .andReturn();
        assertThat(result.getModelAndView().getModel().get("currentUser")).isSameAs(user);
    }
}
