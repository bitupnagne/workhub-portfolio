package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.worksystem.dto.UserUpdateForm;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.*;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;

/** Exercise the browser's urlencoded form through Spring MVC instead of constructing the record directly. */
class PersonnelFormBindingTest {
    private final UserMapper users = mock(UserMapper.class);
    private final OrganizationService organization = mock(OrganizationService.class);
    private final PersonnelController controller = spy(new PersonnelController(users, organization,
            mock(HrScopeService.class), mock(EmpGradeService.class), mock(UserChangeLogService.class),
            mock(PersonnelTransferService.class)));
    private final AtomicReference<BindingResult> binding = new AtomicReference<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var account = new UserAccount();
        account.setId(1L);
        account.setRoles(List.of("SYSTEM_ADMIN"));
        var user = new CustomUserDetails(account);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "", user.getAuthorities()));
        when(organization.canManageOrganization(any())).thenReturn(true);
        var target = new UserAccount();
        target.setId(202L);
        when(users.findById(202L)).thenReturn(target);
        doAnswer(call -> {
            binding.set(call.getArgument(3));
            return call.callRealMethod();
        }).when(controller).update(any(), any(), any(), any(), any());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void regularEmployeeWithUncheckedContractorAndBlankOptionalFieldsCanSave() throws Exception {
        var result = mvc.perform(post("/people/202")
                .param("name", "김직원").param("dept", "개발")
                .param("email", "changed@example.com").param("teamId", "10")
                .param("gradeId", "").param("hireDate", "").param("active", "true"))
                .andReturn();
        assertThat(binding.get().getAllErrors()).isEmpty();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/people/202");
        assertThat(result.getFlashMap().get("message")).isEqualTo("인사정보를 저장했습니다.");
        assertThat(result.getFlashMap().containsKey("error")).isFalse();
        var saved = ArgumentCaptor.forClass(UserUpdateForm.class);
        verify(organization).updateUser(saved.capture(), any());
        assertThat(saved.getValue().active()).isTrue();
        assertThat(saved.getValue().contractor()).isFalse();
        assertThat(saved.getValue().hireDate()).isNull();
        assertThat(saved.getValue().email()).isEqualTo("changed@example.com");
    }

    @Test
    void bothUncheckedFlagsAndHtmlDateAreBoundWithoutLosingEnteredDate() throws Exception {
        var result = mvc.perform(post("/people/202")
                .param("name", "김직원").param("dept", "개발")
                .param("email", "changed@example.com").param("hireDate", "2025-01-15"))
                .andReturn();
        assertThat(binding.get().getAllErrors()).isEmpty();
        assertThat(result.getFlashMap().get("message")).isEqualTo("인사정보를 저장했습니다.");
        var saved = ArgumentCaptor.forClass(UserUpdateForm.class);
        verify(organization).updateUser(saved.capture(), any());
        assertThat(saved.getValue().active()).isFalse();
        assertThat(saved.getValue().contractor()).isFalse();
        assertThat(saved.getValue().hireDate()).isEqualTo(LocalDate.of(2025, 1, 15));
    }

    @Test
    void checkedContractorAndActiveFlagsAreBothPersisted() throws Exception {
        var result = mvc.perform(post("/people/202").param("name", "김직원").param("dept", "개발")
                .param("active", "true").param("contractor", "true")).andReturn();
        assertThat(binding.get().getAllErrors()).isEmpty();
        assertThat(result.getFlashMap().get("message")).isEqualTo("인사정보를 저장했습니다.");
        var saved = ArgumentCaptor.forClass(UserUpdateForm.class);
        verify(organization).updateUser(saved.capture(), any());
        assertThat(saved.getValue().active()).isTrue();
        assertThat(saved.getValue().contractor()).isTrue();
    }

    @Test
    void invalidEmailIsStillRejectedBeforeUpdatingEmployee() throws Exception {
        var result = mvc.perform(post("/people/202").param("name", "김직원").param("dept", "개발")
                .param("email", "invalid-email").param("active", "true")).andReturn();
        assertThat(binding.get().hasFieldErrors("email")).isTrue();
        assertThat(result.getFlashMap().containsKey("error")).isTrue();
        assertThat(result.getFlashMap().containsKey("message")).isFalse();
        verify(organization, never()).updateUser(any(), any());
    }
}
