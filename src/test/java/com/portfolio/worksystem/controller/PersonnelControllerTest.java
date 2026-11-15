package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.portfolio.worksystem.dto.UserUpdateForm;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BindingResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class PersonnelControllerTest {
    private final UserMapper users = mock(UserMapper.class);
    private final OrganizationService organization = mock(OrganizationService.class);
    private final HrScopeService scope = mock(HrScopeService.class);
    private final UserChangeLogService changes = mock(UserChangeLogService.class);
    private final PersonnelController controller = new PersonnelController(users, organization, scope,
            mock(EmpGradeService.class), changes);
    private final CustomUserDetails viewer = mock(CustomUserDetails.class);

    @Test
    void ordinaryDirectoryIsForbiddenBeforeReadingAnyEmployeeDataForEveryQueryVariant() {
        for (String query : new String[]{null, "검색", "", "   "}) {
            for (String view : new String[]{null, "list", "other"}) {
                assertThatThrownBy(() -> controller.directory(viewer, query, view, new ExtendedModelMap()))
                        .isInstanceOf(ResponseStatusException.class)
                        .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));
            }
        }
        verify(organization, never()).findUsers(any());
        verifyNoInteractions(users, scope);
    }

    @Test
    void employeeAdministrationRolesKeepTheDirectoryDefaultIncludingSuperAdminInheritance() {
        when(organization.canManageOrganization(any())).thenCallRealMethod();
        when(organization.findUsers(any())).thenReturn(java.util.List.of());
        when(scope.memberScopeUserIds(any())).thenReturn(java.util.Set.of());
        for (String role : java.util.List.of("HR_ADMIN", "ERP_ADMIN", "SYSTEM_ADMIN", "SUPER_ADMIN")) {
            UserAccount account = new UserAccount(); account.setId(5L); account.setRoles(java.util.List.of(role));
            var principal = new CustomUserDetails(account);
            var model = new ExtendedModelMap();
            assertThat(controller.directory(principal, null, null, model)).isEqualTo("personnel/directory");
            assertThat(model.get("peopleNav")).isEqualTo("list");
        }
    }

    @Test
    void hiddenOwnCardPolicyDoesNotUnlockTheManagementDirectory() {
        var model = new ExtendedModelMap(); model.addAttribute("personnelCardsAllowed", false);
        assertThatThrownBy(() -> controller.directory(viewer, null, null, model))
                .isInstanceOf(ResponseStatusException.class);
        verify(organization, never()).findUsers(any());
    }

    @Test
    void erpManagementDirectoryLinksMatchItsExistingPersonnelReadPermission() {
        when(organization.canManageOrganization(any())).thenCallRealMethod();
        UserAccount account = new UserAccount(); account.setId(1L); account.setRoles(java.util.List.of("ERP_ADMIN"));
        UserAccount employee = new UserAccount(); employee.setId(2L);
        when(organization.findUsers(any())).thenReturn(java.util.List.of(employee));
        var model = new ExtendedModelMap();
        controller.directory(new CustomUserDetails(account), "", "list", model);
        assertThat(((java.util.Set<?>) model.get("readablePersonIds")).contains(2L)).isTrue();
        verifyNoInteractions(scope);
    }

    @Test
    void authorizedOtherPersonsCardSelectsDirectoryRatherThanOwnCard() {
        when(viewer.getUserId()).thenReturn(1L);
        when(scope.canViewMemberHrCard(viewer, 2L)).thenReturn(true);
        var target = new UserAccount(); target.setId(2L); when(users.findById(2L)).thenReturn(target);
        var model = new ExtendedModelMap();
        assertThat(controller.card(viewer, 2L, model)).isEqualTo("personnel/card");
        assertThat(model.get("peopleNav")).isEqualTo("list");
    }

    @Test
    void outOfScopeCardIsDeniedBeforeReadingPersonnelDetails() {
        when(viewer.getUserId()).thenReturn(1L);
        assertThatThrownBy(() -> controller.card(viewer, 2L, new ExtendedModelMap()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(users);
    }

    @Test
    void ownPersonnelCardRemainsAvailableWithoutHrOverviewPermission() {
        when(viewer.getUserId()).thenReturn(1L);
        UserAccount target = new UserAccount();
        target.setId(1L);
        when(users.findById(1L)).thenReturn(target);
        var model = new ExtendedModelMap();
        assertThat(controller.card(viewer, 1L, model)).isEqualTo("personnel/card");
        assertThat(model.get("peopleNav")).isEqualTo("self");
        verify(scope, never()).canViewMemberHrCard(any(), any());
    }

    @Test
    void personnelHistoryExcludesLoaEventRecordedThroughHrCard() {
        when(viewer.getUserId()).thenReturn(1L);
        UserAccount target = new UserAccount(); target.setId(1L);
        when(users.findById(1L)).thenReturn(target);
        var loa = new com.portfolio.worksystem.model.UserChangeLog(); loa.setField("LOA"); loa.setSource("HR_CARD");
        var name = new com.portfolio.worksystem.model.UserChangeLog(); name.setField("name"); name.setSource("ADMIN");
        when(changes.historyForUser(1L, 200)).thenReturn(java.util.List.of(loa, name));
        var model = new ExtendedModelMap();
        controller.card(viewer, 1L, model);
        assertThat(model.get("changeLogs")).isEqualTo(java.util.List.of(name));
    }

    @Test
    void personnelEditPreservesHrFieldsThatThisEditionCannotChange() {
        when(viewer.getUserId()).thenReturn(1L);
        when(organization.canManageOrganization(viewer)).thenReturn(true);
        UserAccount target = new UserAccount();
        target.setId(2L);
        target.setLoaStart(LocalDate.of(2026, 1, 1));
        target.setLoaEnd(LocalDate.of(2026, 2, 1));
        when(users.findById(2L)).thenReturn(target);
        var form = new PersonnelController.PersonnelUpdateForm("EMP2", "직원", 10L, "개발", "100", "", "개발자", null,
                "employee@example.com", LocalDate.of(2025, 1, 1), true, false);
        assertThat(controller.update(viewer, 2L, form, mock(BindingResult.class), new RedirectAttributesModelMap()))
                .isEqualTo("redirect:/people/2");
        var saved = ArgumentCaptor.forClass(UserUpdateForm.class);
        verify(organization).updateUser(saved.capture(), eq(viewer));
        assertThat(saved.getValue().loaStart()).isEqualTo(target.getLoaStart());
        assertThat(saved.getValue().loaEnd()).isEqualTo(target.getLoaEnd());
    }
}
