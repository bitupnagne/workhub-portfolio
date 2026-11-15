package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.portfolio.worksystem.config.ReleaseFeatures;
import com.portfolio.worksystem.dto.UserForm;
import com.portfolio.worksystem.dto.UserUpdateForm;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.OrganizationService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class AdminEmployeeNavigationTest {
    @Test void employeeManagementCompletesAtItsAuthorizedDirectoryWithoutChangingServiceCalls() throws Exception {
        var organization = mock(OrganizationService.class);
        var constructor = AdminController.class.getConstructors()[0];
        Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(type ->
                type == OrganizationService.class ? organization : (type == ReleaseFeatures.class ? new ReleaseFeatures("0.2") : mock(type))).toArray();
        var controller = (AdminController) constructor.newInstance(dependencies);
        var account = new UserAccount(); account.setId(1L); account.setUsername("erp"); account.setRoles(List.of("ERP_ADMIN"));
        var user = new CustomUserDetails(account); var valid = mock(BindingResult.class);
        var create = new UserForm("employee", "EMP-2", "직원", 10L, "개발", "100", "", "개발", null, "user@example.com", null, false);
        assertThat(controller.createUser(user, create, valid, new RedirectAttributesModelMap())).isEqualTo("redirect:/people?view=list");
        verify(organization).createUser(create, user);
        var update = new UserUpdateForm(2L, "EMP-2", "직원", 10L, "개발", "100", "", "개발", null, "user@example.com", null, null, null, true, false);
        assertThat(controller.updateUser(user, update, valid, "directory", new RedirectAttributesModelMap())).isEqualTo("redirect:/people?view=list");
        verify(organization).updateUser(update, user);
        assertThat(controller.updateUser(user, update, valid, "hr-card", new RedirectAttributesModelMap())).isEqualTo("redirect:/hr/members/2");
        var invalid = mock(BindingResult.class);
        when(invalid.hasErrors()).thenReturn(true);
        when(invalid.getAllErrors()).thenReturn(List.of(new org.springframework.validation.ObjectError("form", "검증 오류")));
        assertThat(controller.updateUser(user, update, invalid, "hr-card", new RedirectAttributesModelMap())).isEqualTo("redirect:/hr/members/2?edit=1");
        assertThat(controller.updateUser(user, update, invalid, "directory", new RedirectAttributesModelMap())).isEqualTo("redirect:/people/2?edit=1");
        assertThat(controller.deleteUser(user, 2L, new RedirectAttributesModelMap())).isEqualTo("redirect:/people?view=list");
        verify(organization).deleteUser(2L, user);
    }
}
