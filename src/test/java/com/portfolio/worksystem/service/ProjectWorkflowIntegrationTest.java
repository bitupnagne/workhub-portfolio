package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.ProjectForm;
import com.portfolio.worksystem.dto.ProjectTaskForm;
import com.portfolio.worksystem.mapper.ProjectMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.*;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class ProjectWorkflowIntegrationTest {
    private static final long OWNER = 92001, ASSIGNEE = 92002, RECEIVER = 92003, OUTSIDER = 92004;
    @Container
    static final MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("project_workflow_test").withUsername("test").withPassword(java.util.UUID.randomUUID().toString());
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static ProjectMapper mapper;
    static UserMapper users;
    @TempDir Path uploads;
    ProjectService service;
    CommunicationService communication;
    SystemSettingsService settings;
    CustomUserDetails owner, assignee, receiver, outsider;
    Long projectId;

    @BeforeAll
    static void database() throws Exception {
        dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var configuration = new Configuration(new Environment("project-core-test", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("com.portfolio.worksystem.model");
        for (String file : List.of("ProjectMapper.xml", "UserMapper.xml")) {
            try (var xml = ProjectWorkflowIntegrationTest.class.getResourceAsStream("/mapper/" + file)) {
                new XMLMapperBuilder(xml, configuration, "mapper/" + file, configuration.getSqlFragments()).parse();
            }
        }
        var sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        mapper = sessions.getMapper(ProjectMapper.class);
        users = sessions.getMapper(UserMapper.class);
        jdbc.update("INSERT INTO teams(id,code,name) VALUES (92001,'project-test-a','프로젝트 A팀'),(92002,'project-test-b','프로젝트 B팀')");
        jdbc.update("INSERT INTO users(id,username,password,name,team_id) VALUES "
                + "(92001,'project-owner','encoded','책임자',92001),(92002,'project-assignee','encoded','기존 담당',92001),"
                + "(92003,'project-receiver','encoded','새 담당',92002),(92004,'project-outsider','encoded','외부 사용자',92002)");
    }

    @BeforeEach
    void setup() {
        for (String table : List.of("project_task_activity", "project_task_evidence", "project_task_handovers", "project_task_comments", "project_task_checklist", "project_task_collaborators", "project_tasks", "project_members", "projects"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE users SET active=TRUE WHERE id BETWEEN 92001 AND 92004");
        owner = user(OWNER, false);
        assignee = user(ASSIGNEE, false);
        receiver = user(RECEIVER, false);
        outsider = user(OUTSIDER, true);
        communication = mock(CommunicationService.class);
        settings = mock(SystemSettingsService.class);
        service = transactional(mapper);
        projectId = service.createProject(owner, projectForm());
    }

    @Test
    void membershipControlsReadsEvenForUnrelatedAdministrators() {
        assertEquals(3, service.project(owner, projectId).getMemberCount());
        assertTrue(service.list(outsider, null).isEmpty());
        assertThrows(AccessDeniedException.class, () -> service.project(outsider, projectId));
        assertThrows(AccessDeniedException.class, () -> service.updateProject(assignee, projectId, projectForm()));
        assertEquals("팀 프로젝트", service.project(receiver, projectId).getName());
    }

    @Test
    void taskAssignmentSeparatesCreatorsWorkFromRecipientAssignment() {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        assertEquals(ASSIGNEE, service.task(owner, projectId, taskId).getAssigneeId());
        List<ProjectActivity> history = service.activities(owner, projectId, taskId);
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(a -> a.getEventType().equals("CREATED") && a.getReportUserId() == OWNER));
        assertTrue(history.stream().anyMatch(a -> a.getEventType().equals("ASSIGNED") && a.getReportUserId() == ASSIGNEE && a.getActorUserId() == OWNER));
        assertTrue(history.stream().allMatch(a -> a.getReportTeamId() == 92001L));
        verify(communication).notifyUser(eq(ASSIGNEE), eq(OWNER), eq("PROJECT_TASK"), anyString(), anyString(), contains("/tasks/" + taskId));
    }

    @Test
    void completedCreationCountsOnlySelfAssignedActualWork() {
        var self = taskForm(OWNER);
        self.setStatus(ProjectTaskStatus.DONE);
        Long ownTask = service.createTask(owner, projectId, self);
        assertEquals(100, service.task(owner, projectId, ownTask).getProgress());
        assertEquals("CREATED_COMPLETED", service.activities(owner, projectId, ownTask).getFirst().getEventType());
        var delegated = taskForm(ASSIGNEE);
        delegated.setStatus(ProjectTaskStatus.DONE);
        Long otherTask = service.createTask(owner, projectId, delegated);
        assertFalse(service.activities(owner, projectId, otherTask).stream().anyMatch(a -> a.getEventType().equals("CREATED_COMPLETED")));
    }

    @Test
    void collaborationPermissionsAndVersionsPreventLostUpdates() {
        var initial = taskForm(ASSIGNEE);
        Long taskId = service.createTask(owner, projectId, initial);
        var denied = editForm(taskId);
        denied.setProgress(40);
        denied.setStatus(ProjectTaskStatus.IN_PROGRESS);
        assertThrows(AccessDeniedException.class, () -> service.updateTask(receiver, projectId, taskId, denied));
        var grant = editForm(taskId);
        grant.setCollaboratorIds(List.of(RECEIVER));
        service.updateTask(owner, projectId, taskId, grant);
        var edit = editForm(taskId);
        edit.setProgress(40);
        edit.setStatus(ProjectTaskStatus.IN_PROGRESS);
        service.updateTask(receiver, projectId, taskId, edit);
        assertThrows(BusinessException.class, () -> service.updateTask(receiver, projectId, taskId, edit));
        assertEquals(40, service.task(owner, projectId, taskId).getProgress());
        assertEquals("STATUS_CHANGED", service.activities(owner, projectId, taskId).getFirst().getEventType());
    }

    @Test
    void directReassignmentAndCrossProjectPathsCannotBypassHandover() {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        var edit = editForm(taskId);
        edit.setAssigneeId(RECEIVER);
        assertThrows(BusinessException.class, () -> service.updateTask(owner, projectId, taskId, edit));
        Long differentProject = service.createProject(owner, projectForm());
        assertThrows(BusinessException.class, () -> service.addComment(owner, differentProject, taskId, "다른 프로젝트 경로"));
        assertEquals(ASSIGNEE, service.task(owner, projectId, taskId).getAssigneeId());
    }

    @Test
    void acceptanceIsRecipientOnlyAndPreservesBothTeamSnapshots() {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        service.requestHandover(assignee, projectId, taskId, RECEIVER, "설계 자료 확인 후 API 작업을 이어주세요.");
        ProjectHandover handover = service.handover(owner, projectId, taskId);
        assertEquals(ASSIGNEE, service.task(owner, projectId, taskId).getAssigneeId());
        assertThrows(AccessDeniedException.class, () -> service.respondHandover(owner, projectId, taskId, handover.getId(), "ACCEPT", ""));
        assertThrows(BusinessException.class, () -> service.requestHandover(assignee, projectId, taskId, RECEIVER, "중복 요청"));
        service.respondHandover(receiver, projectId, taskId, handover.getId(), "ACCEPT", "자료 확인했습니다.");
        assertEquals(RECEIVER, service.task(owner, projectId, taskId).getAssigneeId());
        List<ProjectActivity> history = service.activities(owner, projectId, taskId);
        assertTrue(history.stream().anyMatch(a -> a.getEventType().equals("HANDOVER_COMPLETED") && a.getReportUserId() == ASSIGNEE && a.getReportTeamId() == 92001));
        assertTrue(history.stream().anyMatch(a -> a.getEventType().equals("HANDOVER_ACCEPTED") && a.getReportUserId() == RECEIVER && a.getReportTeamId() == 92002));
        assertThrows(BusinessException.class, () -> service.respondHandover(receiver, projectId, taskId, handover.getId(), "ACCEPT", "중복"));
    }

    @Test
    @Timeout(20)
    void concurrentAcceptsTransferResponsibilityOnlyOnce() throws Exception {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        service.requestHandover(assignee, projectId, taskId, RECEIVER, "동시 응답 테스트");
        Long handoverId = service.handover(owner, projectId, taskId).getId();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> operation = () -> {
                start.await();
                try { service.respondHandover(receiver, projectId, taskId, handoverId, "ACCEPT", "확인"); return true; }
                catch (BusinessException alreadyHandled) { return false; }
            };
            var first = executor.submit(operation);
            var second = executor.submit(operation);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, service.activities(owner, projectId, taskId).stream().filter(a -> a.getEventType().equals("HANDOVER_ACCEPTED")).count());
        assertEquals(1L, service.task(owner, projectId, taskId).getVersion());
    }

    @Test
    void rejectingAndCancellingKeepTheCurrentAssigneeAndAllowRecovery() {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        service.requestHandover(assignee, projectId, taskId, RECEIVER, "인계 요청");
        service.respondHandover(receiver, projectId, taskId, service.handover(owner, projectId, taskId).getId(), "REJECT", "일정을 조정해 주세요.");
        assertEquals(ASSIGNEE, service.task(owner, projectId, taskId).getAssigneeId());
        service.requestHandover(assignee, projectId, taskId, RECEIVER, "수신자 비활성 복구");
        Long pendingId = service.handover(owner, projectId, taskId).getId();
        jdbc.update("UPDATE users SET active=FALSE WHERE id=?", RECEIVER);
        assertThrows(AccessDeniedException.class, () -> service.respondHandover(receiver, projectId, taskId, pendingId, "ACCEPT", ""));
        service.cancelHandover(owner, projectId, taskId, pendingId);
        assertEquals("CANCELLED", service.handover(owner, projectId, taskId).getStatus());
        assertNull(mapper.findPendingHandover(taskId));
        assertEquals(ASSIGNEE, service.task(owner, projectId, taskId).getAssigneeId());
    }

    @Test
    void handoverHistoryRetainsFullPriorRequestsAndResponsesAfterAnotherRequest() {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        String originalSummary = "원문" + "가".repeat(4998);
        String originalResponse = "답변" + "나".repeat(1998);
        service.requestHandover(assignee, projectId, taskId, RECEIVER, originalSummary);
        Long firstId = service.handover(owner, projectId, taskId).getId();
        service.respondHandover(receiver, projectId, taskId, firstId, "REJECT", originalResponse);
        service.requestHandover(owner, projectId, taskId, RECEIVER, "자료를 보완한 두 번째 요청입니다.");

        List<ProjectHandover> history = service.handoverHistory(receiver, projectId, taskId);
        assertEquals(2, history.size());
        assertEquals("PENDING", history.get(0).getStatus());
        assertEquals("책임자", history.get(0).getRequestedByName());
        ProjectHandover previous = history.get(1);
        assertEquals(firstId, previous.getId());
        assertEquals("REJECTED", previous.getStatus());
        assertEquals("기존 담당", previous.getFromUserName());
        assertEquals("새 담당", previous.getToUserName());
        assertEquals("기존 담당", previous.getRequestedByName());
        assertEquals(originalSummary, previous.getSummary());
        assertEquals(originalResponse, previous.getResponse());
        assertNotNull(previous.getRequestedAt());
        assertNotNull(previous.getRespondedAt());
        assertThrows(AccessDeniedException.class, () -> service.handoverHistory(outsider, projectId, taskId));
        Long anotherProject = service.createProject(owner, projectForm());
        assertThrows(BusinessException.class, () -> service.handoverHistory(owner, anotherProject, taskId));
    }

    @Test
    void projectMembershipAndArchiveChangesRespectOutstandingResponsibilities() {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        var remove = projectForm();
        remove.setMemberIds(List.of(RECEIVER));
        assertThrows(BusinessException.class, () -> service.updateProject(owner, projectId, remove));
        var finished = projectForm();
        finished.setStatus("COMPLETED");
        assertThrows(BusinessException.class, () -> service.updateProject(owner, projectId, finished));
        service.requestHandover(assignee, projectId, taskId, RECEIVER, "자료 전달");
        var archive = projectForm();
        archive.setStatus("ARCHIVED");
        assertThrows(BusinessException.class, () -> service.updateProject(owner, projectId, archive));
        service.cancelHandover(owner, projectId, taskId, service.handover(owner, projectId, taskId).getId());
        service.updateProject(owner, projectId, archive);
        assertThrows(BusinessException.class, () -> service.addComment(owner, projectId, taskId, "보관 이후 수정"));
        assertThrows(BusinessException.class, () -> service.createTask(owner, projectId, taskForm(OWNER)));
        service.updateProject(owner, projectId, projectForm());
        service.addComment(receiver, projectId, taskId, "다시 진행합니다.");
        assertEquals(1, service.comments(owner, projectId, taskId).size());
    }

    @Test
    void validationRejectsForeignAssigneesBadProgressAndUnsafeLinks() {
        var foreign = taskForm(OUTSIDER);
        assertThrows(BusinessException.class, () -> service.createTask(owner, projectId, foreign));
        var progress = taskForm(ASSIGNEE);
        progress.setStatus(ProjectTaskStatus.IN_PROGRESS);
        progress.setProgress(101);
        assertThrows(BusinessException.class, () -> service.createTask(owner, projectId, progress));
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        assertThrows(BusinessException.class, () -> service.addLink(owner, projectId, taskId, "위험 링크", "javascript:alert(1)"));
        assertThrows(BusinessException.class, () -> service.addLink(owner, projectId, taskId, "인증 포함", "https://user:password@example.com/path"));
        assertThrows(BusinessException.class, () -> service.addLink(owner, projectId, taskId, "정규화 후 초과", "https://example.com/" + "가".repeat(300)));
        service.addLink(receiver, projectId, taskId, "설계 자료", "https://example.com/design?q=one");
        assertEquals("https://example.com/design?q=one", service.evidence(owner, projectId, taskId).getFirst().getUrl());
        assertEquals(RECEIVER, service.activities(owner, projectId, taskId).getFirst().getReportUserId());
    }

    @Test
    void checklistChangesAreAttributedWithoutOverwritingProgress() {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        service.addChecklist(assignee, projectId, taskId, "API 응답 확인");
        Long itemId = service.checklist(owner, projectId, taskId).getFirst().getId();
        assertThrows(AccessDeniedException.class, () -> service.setChecklist(receiver, projectId, taskId, itemId, true));
        service.setChecklist(assignee, projectId, taskId, itemId, true);
        int eventCount = service.activities(owner, projectId, taskId).size();
        service.setChecklist(assignee, projectId, taskId, itemId, true);
        assertEquals(eventCount, service.activities(owner, projectId, taskId).size());
        assertTrue(service.checklist(owner, projectId, taskId).getFirst().isCompleted());
        assertEquals(0, service.task(owner, projectId, taskId).getProgress());
    }

    @Test
    void uploadUsesOpaqueStorageAndDownloadsRequireMembership() throws Exception {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        byte[] bytes = "프로젝트 설계 자료".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        service.addFile(receiver, projectId, taskId, "설계 근거", new MockMultipartFile("file", "..\\..\\design.txt", "text/html", bytes));
        ProjectEvidence evidence = service.evidence(owner, projectId, taskId).getFirst();
        assertEquals("design.txt", evidence.getOriginalFilename());
        assertEquals("application/octet-stream", evidence.getContentType());
        assertEquals(bytes.length, evidence.getSizeBytes());
        assertFalse(evidence.getStoredFilename().contains("design"));
        try (var downloaded = service.fileResource(owner, projectId, evidence.getId()).getInputStream()) {
            assertArrayEquals(bytes, downloaded.readAllBytes());
        }
        assertThrows(AccessDeniedException.class, () -> service.fileResource(outsider, projectId, evidence.getId()));
        jdbc.update("UPDATE project_task_evidence SET stored_filename='../outside.txt' WHERE id=?", evidence.getId());
        assertThrows(BusinessException.class, () -> service.fileResource(owner, projectId, evidence.getId()));
        verify(settings).validateUploadExtension("design.txt");
    }

    @Test
    void failedFileActivityRollsBackTheRowAndDeletesTheUploadedBytes() throws Exception {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        ProjectMapper failingMapper = spy(mapper);
        doThrow(new IllegalStateException("simulated activity failure")).when(failingMapper).insertActivity(any(ProjectActivity.class));
        ProjectService failing = transactional(failingMapper);
        assertThrows(IllegalStateException.class, () -> failing.addFile(owner, projectId, taskId, "테스트", new MockMultipartFile("file", "test.txt", "text/plain", "test".getBytes())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_task_evidence", Integer.class));
        try (var entries = Files.list(uploads)) { assertEquals(0, entries.count()); }
    }

    @Test
    void longOriginalFilenameProducesAValidBoundedDefaultTitle() {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        String filename = "a".repeat(220) + ".txt";
        service.addFile(owner, projectId, taskId, "", new MockMultipartFile("file", filename, "text/plain", new byte[]{1}));
        ProjectEvidence evidence = service.evidence(owner, projectId, taskId).getFirst();
        assertEquals(200, evidence.getTitle().length());
        assertEquals(filename, evidence.getOriginalFilename());
    }

    @Test
    void oversizedOrBlockedExtensionUploadsProduceNoRowsOrFiles() throws Exception {
        Long taskId = service.createTask(owner, projectId, taskForm(ASSIGNEE));
        var oversized = mock(org.springframework.web.multipart.MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(ProjectService.MAX_FILE_BYTES + 1);
        assertThrows(BusinessException.class, () -> service.addFile(owner, projectId, taskId, "큰 파일", oversized));
        doThrow(new BusinessException("blocked extension")).when(settings).validateUploadExtension("unsafe.exe");
        assertThrows(BusinessException.class, () -> service.addFile(owner, projectId, taskId, "차단 파일", new MockMultipartFile("file", "unsafe.exe", "application/octet-stream", new byte[]{1})));
        assertTrue(service.evidence(owner, projectId, taskId).isEmpty());
        try (var entries = Files.list(uploads)) { assertEquals(0, entries.count()); }
    }

    @Test
    void assignmentNotificationFailureRollsBackTaskAndItsWeeklyActivities() {
        doThrow(new IllegalStateException("simulated notification persistence failure")).when(communication).notifyUser(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> service.createTask(owner, projectId, taskForm(ASSIGNEE)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_tasks", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_task_activity", Integer.class));
    }

    private ProjectService transactional(ProjectMapper projectMapper) {
        ProjectService target = new ProjectService(projectMapper, users, settings, communication);
        ReflectionTestUtils.setField(target, "attachmentDir", uploads.toString());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (ProjectService) factory.getProxy();
    }

    private CustomUserDetails user(long id, boolean admin) {
        UserAccount user = users.findById(id);
        user.setRoles(admin ? List.of("SUPER_ADMIN") : List.of("EMPLOYEE"));
        return new CustomUserDetails(user);
    }

    private ProjectForm projectForm() {
        ProjectForm form = new ProjectForm();
        form.setName("팀 프로젝트");
        form.setDescription("설계와 구현 작업 공유");
        form.setOwnerId(OWNER);
        form.setMemberIds(List.of(ASSIGNEE, RECEIVER));
        form.setStartDate(LocalDate.of(2026, 11, 2));
        form.setDueDate(LocalDate.of(2026, 11, 30));
        return form;
    }

    private ProjectTaskForm taskForm(long assigneeId) {
        ProjectTaskForm form = new ProjectTaskForm();
        form.setTitle("프로젝트 API 구현");
        form.setDescription("요청·응답 확인과 테스트 기록");
        form.setAssigneeId(assigneeId);
        return form;
    }

    private ProjectTaskForm editForm(Long taskId) {
        ProjectTask task = service.task(owner, projectId, taskId);
        ProjectTaskForm form = taskForm(task.getAssigneeId());
        form.setTitle(task.getTitle());
        form.setDescription(task.getDescription());
        form.setStatus(task.getStatus());
        form.setProgress(task.getProgress());
        form.setDueDate(task.getDueDate());
        form.setVersion(task.getVersion());
        form.setCollaboratorIds(service.collaborators(owner, projectId, taskId).stream().map(ProjectPerson::getUserId).toList());
        return form;
    }
}
