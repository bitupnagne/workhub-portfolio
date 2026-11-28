package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.ProjectForm;
import com.portfolio.worksystem.dto.ProjectTaskForm;
import com.portfolio.worksystem.mapper.ProjectMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.*;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.util.AfterCommit;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ProjectService {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ProjectMapper mapper;
    private final UserMapper userMapper;
    private final SystemSettingsService systemSettingsService;
    private final CommunicationService communicationService;

    @Value("${app.upload.project-attachment-dir:uploads/projects}")
    private String attachmentDir;

    public List<Project> list(CustomUserDetails user, String query) {
        return mapper.findProjects(user.getUserId(), optional(query, 200, "검색어"), LocalDate.now(SEOUL));
    }

    public List<ProjectPerson> people() { return mapper.findPeople(); }

    public Project project(CustomUserDetails user, Long projectId) {
        Project project = mapper.findProject(projectId, LocalDate.now(SEOUL));
        requireMember(user, project);
        return project;
    }

    public List<ProjectPerson> members(CustomUserDetails user, Long projectId) {
        project(user, projectId);
        return mapper.findMembers(projectId);
    }

    public List<ProjectTask> tasks(CustomUserDetails user, Long projectId) {
        project(user, projectId);
        return mapper.findTasks(projectId);
    }

    public ProjectTask task(CustomUserDetails user, Long projectId, Long taskId) {
        project(user, projectId);
        return requireTask(mapper.findTask(taskId), projectId);
    }

    public List<ProjectPerson> collaborators(CustomUserDetails user, Long projectId, Long taskId) {
        task(user, projectId, taskId);
        return mapper.findCollaborators(taskId);
    }

    public List<ProjectChecklist> checklist(CustomUserDetails user, Long projectId, Long taskId) {
        task(user, projectId, taskId);
        return mapper.findChecklist(taskId);
    }

    public List<ProjectComment> comments(CustomUserDetails user, Long projectId, Long taskId) {
        task(user, projectId, taskId);
        return mapper.findComments(taskId);
    }

    public List<ProjectEvidence> evidence(CustomUserDetails user, Long projectId, Long taskId) {
        task(user, projectId, taskId);
        return mapper.findEvidence(taskId);
    }

    public List<ProjectActivity> activities(CustomUserDetails user, Long projectId, Long taskId) {
        task(user, projectId, taskId);
        return mapper.findActivities(taskId);
    }

    public ProjectHandover handover(CustomUserDetails user, Long projectId, Long taskId) {
        task(user, projectId, taskId);
        return mapper.findLatestHandover(taskId);
    }

    public List<ProjectHandover> handoverHistory(CustomUserDetails user, Long projectId, Long taskId) {
        task(user, projectId, taskId);
        return mapper.findHandovers(taskId);
    }

    public boolean canManage(CustomUserDetails user, Project project) {
        return Objects.equals(user.getUserId(), project.getOwnerId());
    }

    public boolean canEdit(CustomUserDetails user, Project project, ProjectTask task) {
        return "ACTIVE".equals(project.getStatus()) && (canManage(user, project)
                || Objects.equals(user.getUserId(), task.getAssigneeId())
                || mapper.collaboratorCount(task.getId(), user.getUserId()) > 0);
    }

    @Transactional
    public Long createProject(CustomUserDetails user, ProjectForm form) {
        validateProject(form);
        activeUser(user.getUserId());
        Set<Long> members = validatedPeople(form.getMemberIds());
        members.add(user.getUserId());
        members.add(activeUser(form.getOwnerId()).getId());
        Project project = new Project();
        applyProject(project, form);
        project.setCreatedAt(now());
        project.setUpdatedAt(project.getCreatedAt());
        mapper.insertProject(project);
        for (Long member : members) mapper.insertMember(project.getId(), member, project.getCreatedAt());
        return project.getId();
    }

    @Transactional
    public void updateProject(CustomUserDetails user, Long projectId, ProjectForm form) {
        Project project = lockProject(user, projectId, false);
        if (!canManage(user, project)) throw new AccessDeniedException("프로젝트 책임자만 설정을 변경할 수 있습니다.");
        validateProject(form);
        Set<Long> newMembers = validatedPeople(form.getMemberIds());
        newMembers.add(user.getUserId());
        newMembers.add(activeUser(form.getOwnerId()).getId());
        if (!"ACTIVE".equals(form.getStatus())) {
            if (mapper.pendingHandoversInProject(projectId) > 0) throw new BusinessException("대기 중인 인수인계를 먼저 처리하세요.");
            if ("COMPLETED".equals(form.getStatus()) && mapper.unfinishedTaskCount(projectId) > 0)
                throw new BusinessException("진행 중인 업무를 모두 완료한 뒤 프로젝트를 완료할 수 있습니다.");
        }
        List<ProjectPerson> oldMembers = mapper.findMembers(projectId);
        for (ProjectPerson member : oldMembers) {
            if (!newMembers.contains(member.getUserId()) && mapper.countMemberReferences(projectId, member.getUserId()) > 0)
                throw new BusinessException(member.getName() + "님의 담당 업무·협업·인수인계를 먼저 정리하세요.");
        }
        Set<Long> oldIds = new LinkedHashSet<>();
        oldMembers.forEach(person -> oldIds.add(person.getUserId()));
        for (Long id : newMembers) if (!oldIds.contains(id)) mapper.insertMember(projectId, id, now());
        for (Long id : oldIds) if (!newMembers.contains(id)) mapper.deleteMember(projectId, id);
        applyProject(project, form);
        project.setUpdatedAt(now());
        mapper.updateProject(project);
    }

    @Transactional
    public Long createTask(CustomUserDetails user, Long projectId, ProjectTaskForm form) {
        Project project = lockProject(user, projectId, true);
        validateTaskForm(form);
        requireActiveMember(projectId, form.getAssigneeId());
        Set<Long> collaborators = validateCollaborators(projectId, form.getCollaboratorIds(), form.getAssigneeId());
        ProjectTask task = new ProjectTask();
        task.setProjectId(projectId);
        task.setAssigneeId(form.getAssigneeId());
        applyTask(task, form);
        task.setCreatedBy(user.getUserId());
        task.setCreatedAt(now());
        task.setUpdatedAt(task.getCreatedAt());
        mapper.insertTask(task);
        for (Long collaborator : collaborators) mapper.insertCollaborator(task.getId(), collaborator);
        String creationEvent = Objects.equals(user.getUserId(), task.getAssigneeId()) && task.getStatus() == ProjectTaskStatus.DONE
                ? "CREATED_COMPLETED" : "CREATED";
        record(user, user.getUserId(), project, task, creationEvent, "업무 등록: " + task.getTitle());
        if (!Objects.equals(user.getUserId(), task.getAssigneeId())) {
            record(user, task.getAssigneeId(), project, task, "ASSIGNED", "담당 업무를 배정받음: " + task.getTitle());
            notifyUser(task.getAssigneeId(), user, project, task, "새 담당 업무가 배정되었습니다.");
        }
        return task.getId();
    }

    @Transactional
    public void updateTask(CustomUserDetails user, Long projectId, Long taskId, ProjectTaskForm form) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        requireEdit(user, project, task);
        validateTaskForm(form);
        if (!Objects.equals(form.getAssigneeId(), task.getAssigneeId()))
            throw new BusinessException("담당자는 인수인계 요청과 수락을 통해 변경할 수 있습니다.");
        if (form.getVersion() == null || form.getVersion() != task.getVersion())
            throw new BusinessException("다른 사람이 업무를 변경했습니다. 최신 내용을 확인한 뒤 다시 저장하세요.");
        Set<Long> collaborators = validateCollaborators(projectId, form.getCollaboratorIds(), task.getAssigneeId());
        ProjectTaskStatus oldStatus = task.getStatus();
        int oldProgress = task.getProgress();
        applyTask(task, form);
        task.setUpdatedAt(now());
        if (mapper.updateTask(task) != 1) throw new BusinessException("업무가 변경되었습니다. 새로고침 후 다시 시도하세요.");
        mapper.deleteCollaborators(taskId);
        for (Long collaborator : collaborators) mapper.insertCollaborator(taskId, collaborator);
        String event = oldStatus != task.getStatus() ? "STATUS_CHANGED"
                : oldProgress != task.getProgress() ? "PROGRESS_CHANGED" : "UPDATED";
        String summary = switch (event) {
            case "STATUS_CHANGED" -> "상태 변경: " + oldStatus.getLabel() + " → " + task.getStatusLabel() + " (" + task.getProgress() + "%)";
            case "PROGRESS_CHANGED" -> "진척도 변경: " + oldProgress + "% → " + task.getProgress() + "%";
            default -> "업무 설명·일정·협업자 정보를 수정했습니다.";
        };
        record(user, user.getUserId(), project, task, event, summary);
    }

    @Transactional
    public void addChecklist(CustomUserDetails user, Long projectId, Long taskId, String text) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        requireEdit(user, project, task);
        ProjectChecklist item = new ProjectChecklist();
        item.setTaskId(taskId);
        item.setText(required(text, 500, "체크리스트 내용"));
        item.setCreatedAt(now());
        mapper.insertChecklist(item);
        record(user, user.getUserId(), project, task, "CHECKLIST_ADDED", "체크리스트 추가: " + item.getText());
    }

    @Transactional
    public void setChecklist(CustomUserDetails user, Long projectId, Long taskId, Long itemId, boolean completed) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        requireEdit(user, project, task);
        ProjectChecklist item = mapper.findChecklistItem(itemId);
        if (item == null || !Objects.equals(item.getTaskId(), taskId)) throw new BusinessException("체크리스트 항목을 찾을 수 없습니다.");
        if (item.isCompleted() == completed) return;
        mapper.updateChecklist(itemId, completed);
        record(user, user.getUserId(), project, task, "CHECKLIST_CHANGED", (completed ? "체크리스트 완료: " : "체크리스트 다시 열기: ") + item.getText());
    }

    @Transactional
    public void addComment(CustomUserDetails user, Long projectId, Long taskId, String body) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        ProjectComment comment = new ProjectComment();
        comment.setTaskId(taskId);
        comment.setUserId(user.getUserId());
        comment.setBody(required(body, 5000, "작업 기록"));
        comment.setCreatedAt(now());
        mapper.insertComment(comment);
        record(user, user.getUserId(), project, task, "COMMENTED", "작업 기록: " + brief(comment.getBody(), 1000));
    }

    @Transactional
    public void addLink(CustomUserDetails user, Long projectId, Long taskId, String title, String url) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        ProjectEvidence evidence = newEvidence(user, projectId, taskId, required(title, 200, "자료 제목"));
        evidence.setKind("LINK");
        evidence.setUrl(validatedUrl(url));
        mapper.insertEvidence(evidence);
        record(user, user.getUserId(), project, task, "EVIDENCE_ADDED", "근거 링크 추가: " + evidence.getTitle());
    }

    @Transactional
    public void addFile(CustomUserDetails user, Long projectId, Long taskId, String title, MultipartFile file) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        if (file == null || file.isEmpty()) throw new BusinessException("첨부할 파일을 선택하세요.");
        if (file.getSize() > MAX_FILE_BYTES) throw new BusinessException("파일은 1개당 최대 20MB까지 첨부할 수 있습니다.");
        String filename = cleanFilename(file.getOriginalFilename());
        systemSettingsService.validateUploadExtension(filename);
        ProjectEvidence evidence = newEvidence(user, projectId, taskId,
                title == null || title.isBlank() ? brief(filename, 200) : required(title, 200, "자료 제목"));
        evidence.setKind("FILE");
        evidence.setOriginalFilename(filename);
        evidence.setStoredFilename(UUID.randomUUID().toString());
        evidence.setContentType("application/octet-stream");
        Path root = attachmentRoot();
        Path target = root.resolve(evidence.getStoredFilename()).normalize();
        if (!target.startsWith(root)) throw new BusinessException("첨부 경로가 올바르지 않습니다.");
        try {
            Files.createDirectories(root);
            AfterCommit.deleteUploadOnRollback(target, "프로젝트 첨부");
            long total = 0;
            try (var input = file.getInputStream(); var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_FILE_BYTES) throw new BusinessException("파일은 1개당 최대 20MB까지 첨부할 수 있습니다.");
                    output.write(buffer, 0, read);
                }
            }
            evidence.setSizeBytes(total);
            mapper.insertEvidence(evidence);
            record(user, user.getUserId(), project, task, "EVIDENCE_ADDED", "근거 파일 추가: " + evidence.getTitle());
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(target); } catch (IOException cleanupFailure) { error.addSuppressed(cleanupFailure); }
            if (error instanceof BusinessException business) throw business;
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new BusinessException("첨부파일을 저장하지 못했습니다. 다시 시도해 주세요.");
        }
    }

    public ProjectEvidence file(CustomUserDetails user, Long projectId, Long evidenceId) {
        project(user, projectId);
        ProjectEvidence evidence = mapper.findEvidenceById(evidenceId);
        if (evidence == null || !Objects.equals(evidence.getProjectId(), projectId) || !"FILE".equals(evidence.getKind()))
            throw new BusinessException("첨부파일을 찾을 수 없습니다.");
        return evidence;
    }

    public Resource fileResource(CustomUserDetails user, Long projectId, Long evidenceId) {
        ProjectEvidence evidence = file(user, projectId, evidenceId);
        Path root = attachmentRoot();
        Path target = root.resolve(evidence.getStoredFilename()).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target) || Files.isSymbolicLink(target))
            throw new BusinessException("첨부파일을 읽을 수 없습니다.");
        try { return new UrlResource(target.toUri()); }
        catch (IOException error) { throw new BusinessException("첨부파일을 읽을 수 없습니다."); }
    }

    @Transactional
    public void requestHandover(CustomUserDetails user, Long projectId, Long taskId, Long toUserId, String summary) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        if (!canManage(user, project) && !Objects.equals(task.getAssigneeId(), user.getUserId()))
            throw new AccessDeniedException("담당자 또는 프로젝트 책임자만 인수인계를 요청할 수 있습니다.");
        requireActiveMember(projectId, toUserId);
        if (Objects.equals(task.getAssigneeId(), toUserId)) throw new BusinessException("현재 담당자와 다른 인수자를 선택하세요.");
        if (mapper.findPendingHandover(taskId) != null) throw new BusinessException("이미 대기 중인 인수인계 요청이 있습니다.");
        ProjectHandover handover = new ProjectHandover();
        handover.setTaskId(taskId);
        handover.setFromUserId(task.getAssigneeId());
        handover.setToUserId(toUserId);
        handover.setRequestedBy(user.getUserId());
        handover.setSummary(required(summary, 5000, "인계 내용"));
        handover.setStatus("PENDING");
        handover.setRequestedAt(now());
        mapper.insertHandover(handover);
        record(user, user.getUserId(), project, task, "HANDOVER_REQUESTED", "인수인계 요청: " + brief(handover.getSummary(), 1000));
        notifyUser(toUserId, user, project, task, "업무 인수인계 요청이 도착했습니다.");
    }

    @Transactional
    public void respondHandover(CustomUserDetails user, Long projectId, Long taskId, Long handoverId, String decision, String response) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        ProjectHandover handover = mapper.findHandover(handoverId);
        if (handover == null || !Objects.equals(handover.getTaskId(), taskId)) throw new BusinessException("인수인계 요청을 찾을 수 없습니다.");
        if (!Objects.equals(handover.getToUserId(), user.getUserId())) throw new AccessDeniedException("지정된 인수자만 응답할 수 있습니다.");
        if (!"PENDING".equals(handover.getStatus())) throw new BusinessException("이미 처리된 인수인계 요청입니다.");
        requireActiveMember(projectId, user.getUserId());
        if (!Objects.equals(task.getAssigneeId(), handover.getFromUserId())) throw new BusinessException("담당자가 변경되어 요청을 처리할 수 없습니다.");
        if (!"ACCEPT".equals(decision) && !"REJECT".equals(decision)) throw new BusinessException("수락 또는 반려를 선택하세요.");
        handover.setStatus("ACCEPT".equals(decision) ? "ACCEPTED" : "REJECTED");
        handover.setResponse(optional(response, 2000, "응답 내용"));
        handover.setRespondedAt(now());
        if (mapper.respondHandover(handover) != 1) throw new BusinessException("이미 처리된 인수인계 요청입니다.");
        if ("ACCEPT".equals(decision)) {
            mapper.transferTask(taskId, user.getUserId(), now());
            task.setAssigneeId(user.getUserId());
            record(user, user.getUserId(), project, task, "HANDOVER_ACCEPTED", "업무 인수 완료: " + brief(handover.getSummary(), 1000));
            record(user, handover.getFromUserId(), project, task, "HANDOVER_COMPLETED", "업무 인계 완료: " + user.getName() + "님이 인수를 확인했습니다.");
        } else {
            record(user, user.getUserId(), project, task, "HANDOVER_REJECTED", "인수인계 반려" + (handover.getResponse().isBlank() ? "" : ": " + brief(handover.getResponse(), 1000)));
        }
        notifyUser(handover.getRequestedBy(), user, project, task,
                "ACCEPT".equals(decision) ? "인수인계가 수락되었습니다." : "인수인계가 반려되었습니다.");
    }

    @Transactional
    public void cancelHandover(CustomUserDetails user, Long projectId, Long taskId, Long handoverId) {
        Project project = lockProject(user, projectId, true);
        ProjectTask task = lockTask(projectId, taskId);
        ProjectHandover handover = mapper.findHandover(handoverId);
        if (handover == null || !Objects.equals(handover.getTaskId(), taskId)) throw new BusinessException("인수인계 요청을 찾을 수 없습니다.");
        if (!canManage(user, project) && !Objects.equals(handover.getRequestedBy(), user.getUserId())
                && !Objects.equals(task.getAssigneeId(), user.getUserId()))
            throw new AccessDeniedException("요청자·담당자·프로젝트 책임자만 요청을 취소할 수 있습니다.");
        if (!"PENDING".equals(handover.getStatus())) throw new BusinessException("이미 처리된 인수인계 요청입니다.");
        handover.setStatus("CANCELLED");
        handover.setResponse("인수인계 요청을 취소했습니다.");
        handover.setRespondedAt(now());
        if (mapper.respondHandover(handover) != 1) throw new BusinessException("이미 처리된 인수인계 요청입니다.");
        record(user, user.getUserId(), project, task, "HANDOVER_CANCELLED", "인수인계 요청 취소. 기존 담당자가 업무를 계속합니다.");
    }

    private Project lockProject(CustomUserDetails user, Long projectId, boolean writable) {
        Project project = mapper.lockProject(projectId);
        requireMember(user, project);
        if (writable && !"ACTIVE".equals(project.getStatus())) throw new BusinessException("완료·보관 프로젝트는 읽기 전용입니다. 책임자가 진행 중으로 변경할 수 있습니다.");
        return project;
    }

    private void requireMember(CustomUserDetails user, Project project) {
        if (project == null || user == null || mapper.memberCount(project.getId(), user.getUserId()) == 0)
            throw new AccessDeniedException("참여 중인 프로젝트만 열람할 수 있습니다.");
    }

    private ProjectTask lockTask(Long projectId, Long taskId) { return requireTask(mapper.lockTask(taskId), projectId); }

    private ProjectTask requireTask(ProjectTask task, Long projectId) {
        if (task == null || !Objects.equals(task.getProjectId(), projectId)) throw new BusinessException("프로젝트 업무를 찾을 수 없습니다.");
        return task;
    }

    private void requireEdit(CustomUserDetails user, Project project, ProjectTask task) {
        if (!canEdit(user, project, task)) throw new AccessDeniedException("담당자·협업자·프로젝트 책임자만 업무를 수정할 수 있습니다.");
    }

    private void requireActiveMember(Long projectId, Long userId) {
        activeUser(userId);
        if (mapper.memberCount(projectId, userId) == 0) throw new BusinessException("프로젝트 참여자 중에서 선택하세요.");
    }

    private UserAccount activeUser(Long id) {
        UserAccount user = id == null ? null : userMapper.findById(id);
        if (user == null || !Boolean.TRUE.equals(user.getActive())) throw new BusinessException("활성 사용자를 선택하세요.");
        return user;
    }

    private Set<Long> validatedPeople(List<Long> ids) {
        Set<Long> result = new LinkedHashSet<>();
        if (ids != null) {
            if (ids.size() > 300) throw new BusinessException("참여자는 최대 300명까지 지정할 수 있습니다.");
            for (Long id : ids) result.add(activeUser(id).getId());
        }
        return result;
    }

    private Set<Long> validateCollaborators(Long projectId, List<Long> ids, Long assigneeId) {
        Set<Long> collaborators = validatedPeople(ids);
        for (Long id : collaborators) requireActiveMember(projectId, id);
        collaborators.remove(assigneeId);
        return collaborators;
    }

    private void validateProject(ProjectForm form) {
        required(form.getName(), 160, "프로젝트명");
        optional(form.getDescription(), 10000, "프로젝트 설명");
        if (!Set.of("ACTIVE", "COMPLETED", "ARCHIVED").contains(form.getStatus() == null ? "" : form.getStatus()))
            throw new BusinessException("프로젝트 상태를 확인하세요.");
        if (form.getStartDate() != null && form.getDueDate() != null && form.getDueDate().isBefore(form.getStartDate()))
            throw new BusinessException("프로젝트 종료일은 시작일 이후로 설정하세요.");
    }

    private void applyProject(Project project, ProjectForm form) {
        project.setName(required(form.getName(), 160, "프로젝트명"));
        project.setDescription(optional(form.getDescription(), 10000, "프로젝트 설명"));
        project.setOwnerId(form.getOwnerId());
        project.setStartDate(form.getStartDate());
        project.setDueDate(form.getDueDate());
        project.setStatus(form.getStatus());
    }

    private void validateTaskForm(ProjectTaskForm form) {
        required(form.getTitle(), 200, "업무명");
        optional(form.getDescription(), 20000, "업무 설명");
        if (form.getStatus() == null) throw new BusinessException("업무 상태를 선택하세요.");
        if (form.getProgress() == null || form.getProgress() < 0 || form.getProgress() > 100)
            throw new BusinessException("진척도는 0부터 100 사이로 입력하세요.");
        if (form.getStatus() != ProjectTaskStatus.DONE && form.getStatus() != ProjectTaskStatus.TODO && form.getProgress() == 100)
            throw new BusinessException("진척도 100% 업무는 완료 상태로 변경하세요.");
    }

    private void applyTask(ProjectTask task, ProjectTaskForm form) {
        task.setTitle(required(form.getTitle(), 200, "업무명"));
        task.setDescription(optional(form.getDescription(), 20000, "업무 설명"));
        task.setStatus(form.getStatus());
        task.setProgress(form.getStatus() == ProjectTaskStatus.DONE ? 100 : form.getStatus() == ProjectTaskStatus.TODO ? 0 : form.getProgress());
        task.setDueDate(form.getDueDate());
    }

    private ProjectEvidence newEvidence(CustomUserDetails user, Long projectId, Long taskId, String title) {
        ProjectEvidence evidence = new ProjectEvidence();
        evidence.setProjectId(projectId);
        evidence.setTaskId(taskId);
        evidence.setTitle(title);
        evidence.setUploadedBy(user.getUserId());
        evidence.setCreatedAt(now());
        return evidence;
    }

    private void record(CustomUserDetails actor, Long reportUserId, Project project, ProjectTask task, String event, String summary) {
        UserAccount reportUser = userMapper.findById(reportUserId);
        if (reportUser == null) throw new BusinessException("활동 작성자를 찾을 수 없습니다.");
        ProjectActivity activity = new ProjectActivity();
        activity.setProjectId(project.getId());
        activity.setTaskId(task.getId());
        activity.setActorUserId(actor.getUserId());
        activity.setReportUserId(reportUserId);
        activity.setReportTeamId(reportUser.getTeamId());
        activity.setEventType(event);
        activity.setSummary(summary);
        activity.setProjectName(project.getName());
        activity.setTaskTitle(task.getTitle());
        activity.setTaskStatus(task.getStatus().name());
        activity.setProgress(task.getProgress());
        activity.setDueDate(task.getDueDate());
        activity.setOccurredAt(now());
        mapper.insertActivity(activity);
    }

    private void notifyUser(Long recipient, CustomUserDetails sender, Project project, ProjectTask task, String title) {
        if (!Objects.equals(recipient, sender.getUserId()))
            communicationService.notifyUser(recipient, sender.getUserId(), "PROJECT_TASK", title,
                    project.getName() + " · " + task.getTitle(), "/projects/" + project.getId() + "/tasks/" + task.getId());
    }

    private Path attachmentRoot() { return Path.of(attachmentDir).toAbsolutePath().normalize(); }
    private static LocalDateTime now() { return LocalDateTime.now(SEOUL); }
    private static String brief(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private static String required(String value, int max, String label) {
        String result = optional(value, max, label);
        if (result.isBlank()) throw new BusinessException(label + "을(를) 입력하세요.");
        return result;
    }
    private static String optional(String value, int max, String label) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) throw new BusinessException(label + "은(는) " + max + "자까지 입력할 수 있습니다.");
        return result;
    }
    static String validatedUrl(String value) {
        String url = required(value, 2000, "자료 주소");
        try {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || url.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException();
            String normalized = uri.toASCIIString();
            if (normalized.length() > 2000) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException error) { throw new BusinessException("http 또는 https로 시작하는 올바른 자료 주소를 입력하세요."); }
    }
    static String cleanFilename(String raw) {
        String value = raw == null ? "attachment" : raw.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) value = "attachment";
        if (value.length() > 255) throw new BusinessException("첨부파일 이름은 255자까지 사용할 수 있습니다.");
        return value;
    }
}
