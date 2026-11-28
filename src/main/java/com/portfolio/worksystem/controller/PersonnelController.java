package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.UserUpdateForm;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.EmpGradeService;
import com.portfolio.worksystem.service.HrScopeService;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.PersonnelTransferService;
import com.portfolio.worksystem.service.UserChangeLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Personnel-only directory and cards. No attendance, leave, trip or approval data is loaded here. */
@Controller
@RequiredArgsConstructor
public class PersonnelController {
    private final UserMapper userMapper;
    private final OrganizationService organizationService;
    private final HrScopeService hrScopeService;
    private final EmpGradeService empGradeService;
    private final UserChangeLogService userChangeLogService;
    private final PersonnelTransferService personnelTransferService;

    @GetMapping("/people")
    public String directory(@AuthenticationPrincipal CustomUserDetails user,
                            @RequestParam(required = false, name = "q") String query,
                            @RequestParam(required = false, name = "view") String view, Model model) {
        if (!organizationService.canManageOrganization(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        common(model, user, "직원 목록");
        model.addAttribute("peopleNav", "list");
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var cards = organizationService.findUsers(user).stream()
                .filter(employee -> keyword.isEmpty() || searchable(employee).contains(keyword)).toList();
        model.addAttribute("people", cards);
        model.addAttribute("query", query == null ? "" : query.trim());
        var readable = cards.stream().map(UserAccount::getId).collect(java.util.stream.Collectors.toSet());
        model.addAttribute("readablePersonIds", readable);
        return "personnel/directory";
    }

    @GetMapping("/people/me")
    public String ownCard(@AuthenticationPrincipal CustomUserDetails user) {
        return "redirect:/people/" + user.getUserId();
    }

    @GetMapping("/people/{id}")
    public String card(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, Model model) {
        UserAccount target = readableTarget(user, id);
        common(model, user, "인사카드");
        model.addAttribute("peopleNav", Objects.equals(user.getUserId(), id) ? "self" : "list");
        target.setRoles(userMapper.findRolesByUserId(id));
        model.addAttribute("target", target);
        model.addAttribute("editTeams", organizationService.findMembershipTeams());
        model.addAttribute("editGrades", empGradeService.selectableGrades(target.getGradeId()));
        model.addAttribute("changeLogs", userChangeLogService.historyForUser(id, 200).stream()
                .filter(change -> change.getField() != null && java.util.Set.of("name", "employeeNumber", "teamName", "gradeName", "jobTitle", "dept",
                        "extensionNumber", "mobileNumber", "email", "hireDate", "resignedOn", "contractor", "active").contains(change.getField()))
                .filter(change -> !"LOA".equals(change.getSource())).toList());
        model.addAttribute("transferHistory", personnelTransferService.historyForUser(id));
        return "personnel/card";
    }

    @PostMapping("/people/{id}")
    public String update(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id,
                         @Valid @ModelAttribute PersonnelUpdateForm form, BindingResult validation,
                         RedirectAttributes flash) {
        if (!organizationService.canManageOrganization(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        UserAccount target = readableTarget(user, id);
        if (validation.hasErrors()) {
            flash.addFlashAttribute("error", "이름·부서와 이메일 형식을 확인해 주세요.");
        } else {
            // Preserve HR state that this personnel-only form cannot edit, including when migrating an existing DB.
            organizationService.updateUser(new UserUpdateForm(id, form.employeeNumber(), form.name(), form.teamId(),
                    form.dept(), form.extensionNumber(), form.mobileNumber(), form.jobTitle(), form.gradeId(), form.email(),
                    form.hireDate(), target.getLoaStart(), target.getLoaEnd(),
                    Boolean.TRUE.equals(form.active()), Boolean.TRUE.equals(form.contractor())), user);
            flash.addFlashAttribute("message", "인사정보를 저장했습니다.");
        }
        return "redirect:/people/" + id;
    }

    private UserAccount readableTarget(CustomUserDetails user, Long id) {
        if (!Objects.equals(user.getUserId(), id) && !organizationService.canManageOrganization(user) && !hrScopeService.canViewMemberHrCard(user, id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        UserAccount target = userMapper.findById(id);
        if (target == null || (Boolean.TRUE.equals(target.getSystemAccount()) && !Objects.equals(user.getUserId(), id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return target;
    }

    private void common(Model model, CustomUserDetails user, String title) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", title);
        model.addAttribute("active", "people");
        model.addAttribute("showWeekControl", false);
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
        model.addAttribute("canManageWeeklyReport", false);
    }

    private String searchable(UserAccount employee) {
        return String.join(" ", Objects.toString(employee.getName(), ""), Objects.toString(employee.getTeamName(), ""),
                Objects.toString(employee.getDept(), ""), Objects.toString(employee.getJobTitle(), "")).toLowerCase(Locale.ROOT);
    }

    public record PersonnelUpdateForm(String employeeNumber, @NotBlank String name, Long teamId, @NotBlank String dept,
                                      String extensionNumber, String mobileNumber, String jobTitle, Long gradeId,
                                      @Email String email, LocalDate hireDate, Boolean active, Boolean contractor) {}
}
