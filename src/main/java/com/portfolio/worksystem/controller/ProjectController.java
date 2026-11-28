package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.ProjectForm;
import com.portfolio.worksystem.dto.ProjectTaskForm;
import com.portfolio.worksystem.model.*;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.ProjectService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService service;
    private final OrganizationService organizationService;

    @GetMapping("/projects")
    public String list(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam(required = false) String q, Model model) {
        common(model, user, "프로젝트 관리");
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("projects", service.list(user, q));
        model.addAttribute("people", service.people());
        if (!model.containsAttribute("projectForm")) {
            ProjectForm form = new ProjectForm();
            form.setOwnerId(user.getUserId());
            model.addAttribute("projectForm", form);
        }
        return "projects/list";
    }

    @PostMapping("/projects")
    public String createProject(@AuthenticationPrincipal CustomUserDetails user,
                                @Valid @ModelAttribute ProjectForm projectForm, BindingResult errors,
                                RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            formError(redirect, "projectForm", projectForm, errors);
            return "redirect:/projects";
        }
        try {
            Long id = service.createProject(user, projectForm);
            redirect.addFlashAttribute("message", "프로젝트를 만들었습니다.");
            return projectRedirect(id);
        } catch (BusinessException error) {
            formError(redirect, "projectForm", projectForm, error.getMessage());
            return "redirect:/projects";
        }
    }

    @GetMapping("/projects/{projectId}")
    public String detail(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId, Model model) {
        Project project = service.project(user, projectId);
        common(model, user, project.getName());
        model.addAttribute("project", project);
        var members = service.members(user, projectId);
        model.addAttribute("members", members);
        model.addAttribute("people", service.people());
        model.addAttribute("tasks", service.tasks(user, projectId));
        model.addAttribute("taskStatuses", ProjectTaskStatus.values());
        model.addAttribute("canManageProject", service.canManage(user, project));
        model.addAttribute("canContributeProject", "ACTIVE".equals(project.getStatus()));
        if (!model.containsAttribute("projectForm")) {
            ProjectForm form = new ProjectForm();
            form.setName(project.getName());
            form.setDescription(project.getDescription());
            form.setOwnerId(project.getOwnerId());
            form.setStartDate(project.getStartDate());
            form.setDueDate(project.getDueDate());
            form.setStatus(project.getStatus());
            form.setMemberIds(members.stream().map(ProjectPerson::getUserId).toList());
            model.addAttribute("projectForm", form);
        }
        if (!model.containsAttribute("taskForm")) {
            ProjectTaskForm form = new ProjectTaskForm();
            form.setAssigneeId(user.getUserId());
            model.addAttribute("taskForm", form);
        }
        return "projects/detail";
    }

    @PostMapping("/projects/{projectId}")
    public String updateProject(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                                @Valid @ModelAttribute ProjectForm projectForm, BindingResult errors,
                                RedirectAttributes redirect) {
        if (errors.hasErrors()) formError(redirect, "projectForm", projectForm, errors);
        else try {
            service.updateProject(user, projectId, projectForm);
            redirect.addFlashAttribute("message", "프로젝트 설정을 저장했습니다.");
        } catch (BusinessException error) { formError(redirect, "projectForm", projectForm, error.getMessage()); }
        return projectRedirect(projectId);
    }

    @PostMapping("/projects/{projectId}/tasks")
    public String createTask(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                             @Valid @ModelAttribute ProjectTaskForm taskForm, BindingResult errors,
                             RedirectAttributes redirect) {
        if (errors.hasErrors()) {
            formError(redirect, "taskForm", taskForm, errors);
            return projectRedirect(projectId);
        }
        try {
            Long taskId = service.createTask(user, projectId, taskForm);
            redirect.addFlashAttribute("message", "업무를 등록했습니다. 이번 주 보고에 활동이 자동 반영됩니다.");
            return taskRedirect(projectId, taskId);
        } catch (BusinessException error) {
            formError(redirect, "taskForm", taskForm, error.getMessage());
            return projectRedirect(projectId);
        }
    }

    @GetMapping("/projects/{projectId}/tasks/{taskId}")
    public String task(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                       @PathVariable Long taskId, Model model) {
        Project project = service.project(user, projectId);
        ProjectTask task = service.task(user, projectId, taskId);
        var collaborators = service.collaborators(user, projectId, taskId);
        ProjectHandover handover = service.handover(user, projectId, taskId);
        boolean active = "ACTIVE".equals(project.getStatus());
        common(model, user, task.getTitle());
        model.addAttribute("project", project);
        model.addAttribute("task", task);
        model.addAttribute("members", service.members(user, projectId));
        model.addAttribute("collaborators", collaborators);
        model.addAttribute("checklist", service.checklist(user, projectId, taskId));
        model.addAttribute("activities", service.activities(user, projectId, taskId));
        model.addAttribute("comments", service.comments(user, projectId, taskId));
        model.addAttribute("evidence", service.evidence(user, projectId, taskId));
        model.addAttribute("handover", handover);
        model.addAttribute("handoverHistory", service.handoverHistory(user, projectId, taskId));
        model.addAttribute("taskStatuses", ProjectTaskStatus.values());
        model.addAttribute("canManageProject", service.canManage(user, project));
        model.addAttribute("canContributeProject", active);
        model.addAttribute("canEditTask", service.canEdit(user, project, task));
        boolean pending = handover != null && "PENDING".equals(handover.getStatus());
        model.addAttribute("canRequestHandover", active && !pending && (service.canManage(user, project) || Objects.equals(user.getUserId(), task.getAssigneeId())));
        model.addAttribute("canRespondHandover", active && pending && Objects.equals(user.getUserId(), handover.getToUserId()));
        model.addAttribute("canCancelHandover", active && pending && (service.canManage(user, project)
                || Objects.equals(user.getUserId(), task.getAssigneeId()) || Objects.equals(user.getUserId(), handover.getRequestedBy())));
        if (!model.containsAttribute("taskForm")) {
            ProjectTaskForm form = new ProjectTaskForm();
            form.setTitle(task.getTitle());
            form.setDescription(task.getDescription());
            form.setAssigneeId(task.getAssigneeId());
            form.setStatus(task.getStatus());
            form.setProgress(task.getProgress());
            form.setDueDate(task.getDueDate());
            form.setCollaboratorIds(collaborators.stream().map(ProjectPerson::getUserId).toList());
            form.setVersion(task.getVersion());
            model.addAttribute("taskForm", form);
        }
        return "projects/task";
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}")
    public String updateTask(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                             @PathVariable Long taskId, @Valid @ModelAttribute ProjectTaskForm taskForm,
                             BindingResult errors, RedirectAttributes redirect) {
        if (errors.hasErrors()) formError(redirect, "taskForm", taskForm, errors);
        else try {
            service.updateTask(user, projectId, taskId, taskForm);
            redirect.addFlashAttribute("message", "업무를 저장했습니다.");
        } catch (BusinessException error) { formError(redirect, "taskForm", taskForm, error.getMessage()); }
        return taskRedirect(projectId, taskId);
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/checklist")
    public String addChecklist(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                                @PathVariable Long taskId, @RequestParam String text, RedirectAttributes redirect) {
        mutate(redirect, () -> service.addChecklist(user, projectId, taskId, text), "체크리스트를 추가했습니다.");
        if (redirect.getFlashAttributes().containsKey("error")) redirect.addFlashAttribute("draftChecklist", text);
        return taskRedirect(projectId, taskId);
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/checklist/{itemId}")
    public String setChecklist(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                               @PathVariable Long taskId, @PathVariable Long itemId,
                               @RequestParam boolean completed, RedirectAttributes redirect) {
        mutate(redirect, () -> service.setChecklist(user, projectId, taskId, itemId, completed), "체크리스트를 변경했습니다.");
        return taskRedirect(projectId, taskId);
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/comments")
    public String comment(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                          @PathVariable Long taskId, @RequestParam String body, RedirectAttributes redirect) {
        mutate(redirect, () -> service.addComment(user, projectId, taskId, body), "작업 기록을 남겼습니다.");
        if (redirect.getFlashAttributes().containsKey("error")) redirect.addFlashAttribute("draftComment", body);
        return taskRedirect(projectId, taskId);
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/links")
    public String link(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                       @PathVariable Long taskId, @RequestParam String title, @RequestParam String url,
                       RedirectAttributes redirect) {
        mutate(redirect, () -> service.addLink(user, projectId, taskId, title, url), "근거 링크를 추가했습니다.");
        if (redirect.getFlashAttributes().containsKey("error")) {
            redirect.addFlashAttribute("draftLinkTitle", title);
            redirect.addFlashAttribute("draftLinkUrl", url);
        }
        return taskRedirect(projectId, taskId);
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/files")
    public String upload(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                         @PathVariable Long taskId, @RequestParam(required = false) String title,
                         @RequestParam(required = false) MultipartFile file, RedirectAttributes redirect) {
        mutate(redirect, () -> service.addFile(user, projectId, taskId, title, file), "근거 파일을 첨부했습니다.");
        if (redirect.getFlashAttributes().containsKey("error")) redirect.addFlashAttribute("draftFileTitle", title == null ? "" : title);
        return taskRedirect(projectId, taskId);
    }

    @GetMapping("/projects/{projectId}/files/{evidenceId}")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                                             @PathVariable Long evidenceId) {
        ProjectEvidence evidence = service.file(user, projectId, evidenceId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(evidence.getOriginalFilename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(service.fileResource(user, projectId, evidenceId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/handover")
    public String handover(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                           @PathVariable Long taskId, @RequestParam Long toUserId, @RequestParam String summary,
                           RedirectAttributes redirect) {
        mutate(redirect, () -> service.requestHandover(user, projectId, taskId, toUserId, summary), "인수인계를 요청했습니다. 수락 전까지 현재 담당자가 유지됩니다.");
        if (redirect.getFlashAttributes().containsKey("error")) {
            redirect.addFlashAttribute("draftHandoverSummary", summary);
            redirect.addFlashAttribute("draftHandoverToUserId", toUserId);
        }
        return taskRedirect(projectId, taskId);
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/handover/{handoverId}/respond")
    public String respond(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                          @PathVariable Long taskId, @PathVariable Long handoverId, @RequestParam String decision,
                          @RequestParam(required = false) String response, RedirectAttributes redirect) {
        mutate(redirect, () -> service.respondHandover(user, projectId, taskId, handoverId, decision, response), "인수인계 응답을 저장했습니다.");
        if (redirect.getFlashAttributes().containsKey("error")) redirect.addFlashAttribute("draftHandoverResponse", response == null ? "" : response);
        return taskRedirect(projectId, taskId);
    }

    private void common(Model model, CustomUserDetails user, String title) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", title);
        model.addAttribute("headerTitle", title);
        model.addAttribute("active", "projects");
        model.addAttribute("activeService", "projects");
        model.addAttribute("showWeekControl", false);
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
        model.addAttribute("fileMaxBytes", ProjectService.MAX_FILE_BYTES);
        model.addAttribute("fileUploadHint", "파일 1개당 최대 20MB");
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/handover/{handoverId}/cancel")
    public String cancelHandover(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long projectId,
                                @PathVariable Long taskId, @PathVariable Long handoverId, RedirectAttributes redirect) {
        mutate(redirect, () -> service.cancelHandover(user, projectId, taskId, handoverId), "인수인계 요청을 취소했습니다.");
        return taskRedirect(projectId, taskId);
    }

    private void mutate(RedirectAttributes redirect, Runnable operation, String message) {
        try { operation.run(); redirect.addFlashAttribute("message", message); }
        catch (BusinessException error) { redirect.addFlashAttribute("error", error.getMessage()); }
    }
    private void formError(RedirectAttributes redirect, String name, Object form, BindingResult errors) {
        String message = errors.getAllErrors().stream().map(error -> error.getDefaultMessage() == null ? "입력값을 확인하세요." : error.getDefaultMessage()).findFirst().orElse("입력값을 확인하세요.");
        formError(redirect, name, form, message);
        redirect.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + name, errors);
    }
    private void formError(RedirectAttributes redirect, String name, Object form, String message) {
        redirect.addFlashAttribute(name, form);
        redirect.addFlashAttribute(name + "Error", true);
        redirect.addFlashAttribute("error", message);
    }
    private String projectRedirect(Long id) { return "redirect:/projects/" + id; }
    private String taskRedirect(Long projectId, Long taskId) { return projectRedirect(projectId) + "/tasks/" + taskId; }
}
