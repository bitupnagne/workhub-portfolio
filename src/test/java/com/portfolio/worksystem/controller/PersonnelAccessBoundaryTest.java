package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.portfolio.worksystem.config.ReleaseFeatures;
import com.portfolio.worksystem.config.SecurityConfig;
import com.portfolio.worksystem.dto.RightPanelPayload;
import com.portfolio.worksystem.dto.RightPanelGroup;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.EmployeePresence;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.security.LoginAttemptService;
import com.portfolio.worksystem.service.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Actual SecurityConfig filter chain and actual MVC handlers; DB dependencies are controlled at the service boundary. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = PersonnelAccessBoundaryTest.App.class)
class PersonnelAccessBoundaryTest {
    @Autowired WebApplicationContext context;
    @Autowired OrganizationService organization;
    @Autowired UserMapper users;
    @Autowired HrScopeService scope;
    @Autowired CommunicationService communication;
    MockMvc mvc;

    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, ReleaseFeatures.class, PersonnelController.class,
            OrganizationChartController.class, CommunicationApiController.class})
    static class App {
        @Bean OrganizationService organization() { return mock(OrganizationService.class); }
        @Bean UserMapper users() { return mock(UserMapper.class); }
        @Bean HrScopeService scope() { return mock(HrScopeService.class); }
        @Bean EmpGradeService grades() { return mock(EmpGradeService.class); }
        @Bean UserChangeLogService changes() { return mock(UserChangeLogService.class); }
        @Bean CommunicationService communication() { return mock(CommunicationService.class); }
        @Bean SystemSettingsService settings() { return mock(SystemSettingsService.class); }
        @Bean LoginAttemptService attempts() { return mock(LoginAttemptService.class); }
        @Bean UserDetailsService loginUsers() {
            var account = account(9L, "EMPLOYEE"); account.setUsername("home-test");
            account.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test-password"));
            return new InMemoryUserDetailsManager(new CustomUserDetails(account));
        }
    }
    static UserAccount account(Long id, String role) {
        var account = new UserAccount(); account.setId(id); account.setUsername("user-" + id);
        account.setName("테스트 직원"); account.setRoles(List.of(role)); account.setActive(true); return account;
    }
    CustomUserDetails principal(String role) { return new CustomUserDetails(account(1L, role)); }

    @BeforeEach void setup() {
        reset(organization, users, scope, communication);
        when(organization.canManageOrganization(any())).thenCallRealMethod();
        when(organization.findUsers(any())).thenReturn(List.of(account(2L, "EMPLOYEE")));
        when(users.findById(anyLong())).thenAnswer(call -> account(call.getArgument(0), "EMPLOYEE"));
        when(users.findRolesByUserId(anyLong())).thenReturn(List.of("EMPLOYEE"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void nonManagersCannotListOrSearchEvenWithJsonFetchOrBlankParameters() throws Exception {
        for (String role : List.of("EMPLOYEE", "TEAM_LEADER", "EVAL_ADMIN", "REPORT_ADMIN", "NOTICE_ADMIN")) {
            for (String path : List.of("/people", "/people?view=list", "/people?q=", "/people?q=%20%20", "/people?view=list&q=test")) {
                mvc.perform(get(path).with(user(principal(role))).header("X-Requested-With", "fetch")
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
            }
        }
        verify(organization, never()).findUsers(any()); verifyNoInteractions(users, scope);
    }

    @Test void exactManagementRolesCanSearchAndOpenExistingPersonnelCardsIncludingErpAndSuperInheritance() throws Exception {
        for (String role : List.of("HR_ADMIN", "ERP_ADMIN", "SYSTEM_ADMIN", "SUPER_ADMIN")) {
            mvc.perform(get("/people").param("q", "").param("view", "list").with(user(principal(role))))
                    .andExpect(status().isOk()).andExpect(view().name("personnel/directory"))
                    .andExpect(model().attribute("readablePersonIds", java.util.Set.of(2L)));
            mvc.perform(get("/people/2").with(user(principal(role))))
                    .andExpect(status().isOk()).andExpect(view().name("personnel/card"));
        }
    }

    @Test void explicitOwnCardRemainsAvailableWhileAnotherPersonsPrivateCardIsForbidden() throws Exception {
        mvc.perform(get("/people/me").with(user(principal("EMPLOYEE"))))
                .andExpect(redirectedUrl("/people/1"));
        mvc.perform(get("/people/1").with(user(principal("EMPLOYEE"))))
                .andExpect(status().isOk()).andExpect(view().name("personnel/card"));
        clearInvocations(users);
        mvc.perform(get("/people/2").with(user(principal("EMPLOYEE")))).andExpect(status().isForbidden());
        verify(users, never()).findById(2L);
    }

    @Test void existingLeaderTargetScopeIsPreservedWithoutGrantingTheManagementDirectory() throws Exception {
        when(scope.canViewMemberHrCard(any(), eq(2L))).thenReturn(true);
        mvc.perform(get("/people/2").with(user(principal("TEAM_LEADER")))).andExpect(status().isOk());
        mvc.perform(get("/people/3").with(user(principal("TEAM_LEADER")))).andExpect(status().isForbidden());
        mvc.perform(get("/people?view=list").with(user(principal("TEAM_LEADER")))).andExpect(status().isForbidden());
        verify(users, never()).findById(3L);
    }

    @Test void organizationAndPublicContactPayloadRemainAvailableWithoutPrivatePersonnelFields() throws Exception {
        var contact = new EmployeePresence(); contact.setUserId(2L); contact.setName("공개 연락처"); contact.setExtensionNumber("100");
        var team = new RightPanelGroup.RightPanelTeam(10L, "공개 팀", List.of(contact));
        when(communication.rightPanel(any())).thenReturn(new RightPanelPayload(1L, 0, 0, List.of(), List.of(),
                List.of(new RightPanelGroup("조직", List.of(team))), List.of()));
        mvc.perform(get("/org").with(user(principal("EMPLOYEE"))))
                .andExpect(status().isOk()).andExpect(view().name("org-chart"));
        var response = mvc.perform(get("/api/right-panel").with(user(principal("EMPLOYEE"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("extensionNumber").doesNotContain("hireDate", "employeeNumber", "password", "otpSecret", "loaStart");
        verify(organization, never()).findUsers(any());
    }

    @Test void unauthenticatedDirectoryAndOrganizationStillRequireLogin() throws Exception {
        mvc.perform(get("/people?q=test")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/org")).andExpect(status().is3xxRedirection());
    }

    @Test void successfulPasswordLoginKeepsFixedDestinationPolicyAndUsesOrganizationHome() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        var previous = new org.springframework.mock.web.MockHttpServletRequest("GET", "/eval/tasks");
        previous.setSession(session);
        new org.springframework.security.web.savedrequest.HttpSessionRequestCache().saveRequest(previous,
                new org.springframework.mock.web.MockHttpServletResponse());
        mvc.perform(post("/login").session(session).param("username", "home-test").param("password", "test-password").with(csrf()))
                .andExpect(redirectedUrl("/org"));
    }
}
