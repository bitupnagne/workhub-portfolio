package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.ReportItemForm;
import com.portfolio.worksystem.mapper.ReportMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.*;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
class ReportProjectIntegrationTest {
    private static final long USER_A = 91001, USER_B = 91002, TEAM_A = 91001, TEAM_B = 91002, TASK = 91001;
    private static final String WEEK = "2026-W45", NEXT_WEEK = "2026-W46";
    @Container
    static final MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("project_reports_test").withUsername("test").withPassword(java.util.UUID.randomUUID().toString());
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static ReportMapper mapper;
    ReportService service;
    CustomUserDetails userA;
    CustomUserDetails userB;

    @BeforeAll
    static void database() throws Exception {
        dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var configuration = new Configuration(new Environment("report-project-test", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("com.portfolio.worksystem.model");
        try (var xml = ReportProjectIntegrationTest.class.getResourceAsStream("/mapper/ReportMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mapper/ReportMapper.xml", configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(ReportMapper.class);
        jdbc.update("INSERT INTO teams(id,code,name) VALUES (?, 'report-test-a', '테스트 A팀'), (?, 'report-test-b', '테스트 B팀')", TEAM_A, TEAM_B);
        jdbc.update("INSERT INTO users(id,username,password,name,team_id) VALUES (?, 'report-test-a', 'encoded', '담당 A', ?), (?, 'report-test-b', 'encoded', '담당 B', ?)", USER_A, TEAM_A, USER_B, TEAM_B);
        jdbc.update("INSERT INTO projects(id,name,description,owner_id,created_at,updated_at) VALUES (?, '연계 테스트', '', ?, NOW(), NOW())", TASK, USER_A);
        jdbc.update("INSERT INTO project_tasks(id,project_id,title,description,assignee_id,created_by,created_at,updated_at) VALUES (?, ?, '실제 업무', '', ?, ?, NOW(), NOW())", TASK, TASK, USER_A, USER_A);
    }

    @BeforeEach
    void prepare() {
        for (String table : List.of("report_item_details", "report_items", "weekly_reports", "team_weekly_status", "weekly_confirmations", "report_week_locks", "weekly_report_snapshots", "report_audit_logs", "project_task_activity"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE users SET team_id = ? WHERE id = ?", TEAM_A, USER_A);
        jdbc.update("UPDATE project_tasks SET assignee_id = ? WHERE id = ?", USER_A, TASK);
        userA = user(USER_A, TEAM_A, "TEAM_LEADER");
        userB = user(USER_B, TEAM_B, "EMPLOYEE");
        OrganizationService organization = mock(OrganizationService.class);
        when(organization.canManageWeeklyReport(any())).thenReturn(true);
        TeamMapper teams = mock(TeamMapper.class);
        when(teams.findAll()).thenReturn(List.of(team(TEAM_A), team(TEAM_B)));
        ReportService target = new ReportService(mapper, mock(UserMapper.class), teams, organization, mock(HrService.class));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        service = (ReportService) factory.getProxy();
    }

    @Test
    void sundayActivityIsIncludedAndNextMondayIsExcludedWithoutDuplicates() {
        event(USER_A, TEAM_A, "CREATED", "TODO", 0, "2026-11-02T00:00:00", "업무 등록");
        event(USER_A, TEAM_A, "PROGRESS_CHANGED", "IN_PROGRESS", 60, "2026-11-08T23:59:59", "일요일 작업 진척 60%");
        event(USER_A, TEAM_A, "STATUS_CHANGED", "DONE", 100, "2026-11-09T00:00:00", "다음 주 완료");
        service.syncProjectActivities(userA, WEEK);
        service.syncProjectActivities(userA, WEEK);
        List<ReportItem> items = service.myFlatItems(USER_A, WEEK);
        assertEquals(1, items.size());
        assertEquals(60, items.getFirst().getProjectProgress());
        assertTrue(items.getFirst().getProjectSummary().contains("일요일 작업"));
        assertFalse(items.getFirst().getProjectSummary().contains("다음 주"));
        service.syncProjectActivities(userA, NEXT_WEEK);
        assertEquals(ItemStatus.DONE, service.myFlatItems(USER_A, NEXT_WEEK).getFirst().getStatus());
    }

    @Test
    void handoverPreservesBothPeoplesActualContributionAndDoesNotAttributeDoneComments() {
        event(USER_A, TEAM_A, "PROGRESS_CHANGED", "IN_PROGRESS", 30, "2026-11-03T09:00:00", "기존 담당자 작업");
        event(USER_A, TEAM_A, "HANDOVER_COMPLETED", "IN_PROGRESS", 30, "2026-11-04T09:00:00", "담당 B에게 인계 완료");
        event(USER_B, TEAM_B, "HANDOVER_ACCEPTED", "IN_PROGRESS", 30, "2026-11-04T09:00:00", "업무 인수 확인");
        event(USER_B, TEAM_B, "STATUS_CHANGED", "DONE", 100, "2026-11-05T09:00:00", "후속 작업 완료");
        event(USER_A, TEAM_A, "COMMENTED", "DONE", 100, "2026-11-06T09:00:00", "결과 확인 댓글");
        jdbc.update("UPDATE project_tasks SET assignee_id=? WHERE id=?", USER_B, TASK);
        service.syncProjectActivities(userA, WEEK);
        service.syncProjectActivities(userB, WEEK);
        ReportItem before = service.myFlatItems(USER_A, WEEK).getFirst();
        ReportItem after = service.myFlatItems(USER_B, WEEK).getFirst();
        assertEquals(ItemStatus.CONTINUE, before.getStatus());
        assertEquals(ItemStatus.DONE, after.getStatus());
        assertTrue(before.getProjectSummary().contains("인계 완료"));
        assertFalse(before.getProjectSummary().contains("후속 작업 완료"));
        assertTrue(after.getProjectSummary().contains("인수 확인"));
        assertEquals(TEAM_A, before.getSourceTeamId());
        assertEquals(TEAM_B, after.getSourceTeamId());
    }

    @Test
    void reportNotesSurviveSynchronizationAndOriginalRowsCannotBeManuallyOverwritten() {
        initialActivity();
        service.syncProjectActivities(userA, WEEK);
        long itemId = service.myFlatItems(USER_A, WEEK).getFirst().getId();
        service.saveProjectNote(userA, itemId, "팀 공유용 보충 설명");
        event(USER_A, TEAM_A, "PROGRESS_CHANGED", "IN_PROGRESS", 80, "2026-11-06T09:00:00", "통합 검증 진행");
        service.syncProjectActivities(userA, WEEK);
        ReportItem item = service.myFlatItems(USER_A, WEEK).getFirst();
        assertEquals("팀 공유용 보충 설명", item.getProjectNote());
        assertEquals(80, item.getProjectProgress());
        assertThrows(BusinessException.class, () -> service.saveItem(new ReportItemForm(itemId, "수동 덮어쓰기", null, null, ItemStatus.DONE, WEEK), userA));
        assertThrows(BusinessException.class, () -> service.deleteItem(itemId, userA));
        assertThrows(BusinessException.class, () -> service.changeMyItemStatus(userA, itemId, ItemStatus.DONE));
        assertThrows(BusinessException.class, () -> service.saveProjectNote(userB, itemId, "다른 사람 메모"));
        assertThrows(BusinessException.class, () -> service.saveProjectNote(userA, itemId, "가".repeat(2001)));
        assertEquals("팀 공유용 보충 설명", service.myFlatItems(USER_A, WEEK).getFirst().getProjectNote());
    }

    @Test
    void submissionFreezesActivityAndRecallRefreshesWithoutLosingNotes() {
        initialActivity();
        service.syncProjectActivities(userA, WEEK);
        long itemId = service.myFlatItems(USER_A, WEEK).getFirst().getId();
        service.saveProjectNote(userA, itemId, "보존할 설명");
        service.submitMyReport(userA, WEEK);
        event(USER_A, TEAM_A, "STATUS_CHANGED", "DONE", 100, "2026-11-07T09:00:00", "제출 후 완료");
        service.syncProjectActivities(userA, WEEK);
        assertEquals(10, service.myFlatItems(USER_A, WEEK).getFirst().getProjectProgress());
        assertThrows(BusinessException.class, () -> service.saveProjectNote(userA, itemId, "잠금 우회"));
        service.recallMyReport(userA, WEEK);
        ReportItem refreshed = service.myFlatItems(USER_A, WEEK).getFirst();
        assertEquals(100, refreshed.getProjectProgress());
        assertEquals("보존할 설명", refreshed.getProjectNote());
    }

    @Test
    void importingLastWeekOnlyReplacesManualRowsAndKeepsCurrentProjectEvidence() {
        initialActivity();
        event(USER_A, TEAM_A, "PROGRESS_CHANGED", "IN_PROGRESS", 50, "2026-11-10T09:00:00", "다음 주 진행");
        service.syncProjectActivities(userA, WEEK);
        service.syncProjectActivities(userA, NEXT_WEEK);
        service.saveItem(new ReportItemForm(null, "지난주 단건 업무", null, null, ItemStatus.CONTINUE, WEEK), userA);
        service.saveItem(new ReportItemForm(null, "교체될 직접 작성", null, null, ItemStatus.NEW, NEXT_WEEK), userA);
        long linkedId = service.myFlatItems(USER_A, NEXT_WEEK).stream().filter(ReportItem::isProjectLinked).findFirst().orElseThrow().getId();
        service.saveProjectNote(userA, linkedId, "유지할 메모");
        service.importFromWeek(userA, WEEK, NEXT_WEEK);
        List<ReportItem> items = service.myFlatItems(USER_A, NEXT_WEEK);
        assertEquals(2, items.size());
        assertTrue(items.stream().anyMatch(item -> "지난주 단건 업무".equals(item.getContent())));
        assertFalse(items.stream().anyMatch(item -> "교체될 직접 작성".equals(item.getContent())));
        ReportItem linked = items.stream().filter(ReportItem::isProjectLinked).findFirst().orElseThrow();
        assertEquals(linkedId, linked.getId());
        assertEquals("유지할 메모", linked.getProjectNote());
        assertTrue(linked.getProjectSummary().contains("다음 주 진행"));
        assertFalse(linked.getProjectSummary().contains("처음 작업"));
    }

    @Test
    void reportingTeamSnapshotSurvivesMembershipChangeAndNullTeamIsSupported() {
        initialActivity();
        service.syncProjectActivities(userA, WEEK);
        jdbc.update("UPDATE users SET team_id=? WHERE id=?", TEAM_B, USER_A);
        event(USER_A, TEAM_B, "PROGRESS_CHANGED", "IN_PROGRESS", 40, "2026-11-05T09:00:00", "이동 후 추가 작업");
        CustomUserDetails moved = user(USER_A, TEAM_B, "EMPLOYEE");
        service.submitMyReport(moved, WEEK);
        assertEquals(1, mapper.findSubmittedItemsByTeam(TEAM_A, WEEK).size());
        assertEquals(0, mapper.findSubmittedItemsByTeam(TEAM_B, WEEK).size());
        assertEquals(TEAM_A, service.myFlatItems(USER_A, WEEK).getFirst().getSourceTeamId());
        event(USER_B, null, "CREATED", "TODO", 0, "2026-11-03T09:00:00", "조직 없는 계정의 업무");
        CustomUserDetails noTeam = user(USER_B, null, "SUPER_ADMIN");
        service.syncProjectActivities(noTeam, WEEK);
        service.submitMyReport(noTeam, WEEK);
        assertNull(service.myFlatItems(USER_B, WEEK).getFirst().getTeamId());
    }

    enum LockState { PERSONAL_SUBMITTED, TEAM_SUBMITTED, WEEK_CONFIRMED }

    @Test
    void movingTeamsCannotRecallPersonalReportOutOfAlreadySubmittedSourceTeam() {
        initialActivity();
        service.submitMyReport(userA, WEEK);
        long originalItemId = service.myFlatItems(USER_A, WEEK).getFirst().getId();
        jdbc.update("UPDATE users SET team_id=? WHERE id=?", TEAM_B, USER_A);
        jdbc.update("UPDATE users SET team_id=? WHERE id=?", TEAM_A, USER_B);
        service.teamSubmit(user(USER_B, TEAM_A, "TEAM_LEADER"), WEEK);
        CustomUserDetails moved = user(USER_A, TEAM_B, "EMPLOYEE");

        BusinessException error = assertThrows(BusinessException.class, () -> service.recallMyReport(moved, WEEK));
        assertTrue(error.getMessage().contains("활동 당시 소속 팀"));
        assertTrue(service.findMyReport(USER_A, WEEK).getSubmitted());
        assertEquals(List.of(originalItemId), mapper.findSubmittedItemsByTeam(TEAM_A, WEEK).stream().map(ReportItem::getId).toList());
        assertFalse(mapper.findTeamStatus(TEAM_B, WEEK) != null && Boolean.TRUE.equals(mapper.findTeamStatus(TEAM_B, WEEK).getSubmitted()));
    }

    @Test
    void movingTeamsCannotSubmitLateOrReorderWorkInsideAlreadySubmittedSourceTeam() {
        initialActivity();
        service.syncProjectActivities(userA, WEEK);
        service.saveItem(new ReportItemForm(null, "별도 직접 작성", null, null, ItemStatus.CONTINUE, WEEK), userA);
        List<ReportItem> before = service.myFlatItems(USER_A, WEEK);
        long manualItemId = before.stream().filter(item -> !item.isProjectLinked()).findFirst().orElseThrow().getId();
        jdbc.update("UPDATE users SET team_id=? WHERE id=?", TEAM_B, USER_A);
        jdbc.update("UPDATE users SET team_id=? WHERE id=?", TEAM_A, USER_B);
        service.teamSubmit(user(USER_B, TEAM_A, "TEAM_LEADER"), WEEK);
        CustomUserDetails moved = user(USER_A, TEAM_B, "EMPLOYEE");

        assertThrows(BusinessException.class, () -> service.submitMyReport(moved, WEEK));
        assertThrows(BusinessException.class, () -> service.moveMyItem(moved, manualItemId, -1));
        assertFalse(service.findMyReport(USER_A, WEEK).getSubmitted());
        assertTrue(mapper.findSubmittedItemsByTeam(TEAM_A, WEEK).isEmpty());
        assertEquals(before.stream().map(ReportItem::getId).toList(), service.myFlatItems(USER_A, WEEK).stream().map(ReportItem::getId).toList());
    }

    @Test
    void dueDateComesFromActivitySnapshotAndStaysFrozenAfterSubmission() {
        initialActivity();
        jdbc.update("UPDATE project_task_activity SET due_date='2026-11-13'");
        jdbc.update("UPDATE project_tasks SET due_date='2026-11-27' WHERE id=?", TASK);
        service.syncProjectActivities(userA, WEEK);
        assertEquals(java.time.LocalDate.of(2026, 11, 13), service.myFlatItems(USER_A, WEEK).getFirst().getDueDate());
        service.submitMyReport(userA, WEEK);
        event(USER_A, TEAM_A, "UPDATED", "IN_PROGRESS", 10, "2026-11-07T09:00:00", "기한 재협의");
        jdbc.update("UPDATE project_task_activity SET due_date='2026-12-04' WHERE summary='기한 재협의'");
        service.syncProjectActivities(userA, WEEK);
        assertEquals(java.time.LocalDate.of(2026, 11, 13), service.myFlatItems(USER_A, WEEK).getFirst().getDueDate());
    }

    @ParameterizedTest
    @EnumSource(LockState.class)
    @Timeout(20)
    void waitingSynchronizationSeesSubmissionCommittedBeforeLockRelease(LockState state) throws Exception {
        initialActivity();
        WeeklyReport report = service.getOrCreateReport(USER_A, WEEK);
        try (Connection lockConnection = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            lockConnection.setAutoCommit(false);
            try {
            try (var statement = lockConnection.prepareStatement("SELECT week_key FROM report_week_locks WHERE week_key=? FOR UPDATE")) {
                statement.setString(1, WEEK);
                statement.executeQuery().close();
            }
            CountDownLatch started = new CountDownLatch(1);
            var waiting = executor.submit(() -> { started.countDown(); service.syncProjectActivities(userA, WEEK); });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(200, TimeUnit.MILLISECONDS), "Synchronization must wait for the week lock");
            String sql = switch (state) {
                case PERSONAL_SUBMITTED -> "UPDATE weekly_reports SET submitted=TRUE WHERE id=" + report.getId();
                case TEAM_SUBMITTED -> "INSERT INTO team_weekly_status(team_id,week_key,submitted) VALUES (" + TEAM_A + ", '" + WEEK + "', TRUE)";
                case WEEK_CONFIRMED -> "INSERT INTO weekly_confirmations(week_key,confirmed) VALUES ('" + WEEK + "', TRUE)";
            };
            try (var statement = lockConnection.createStatement()) { statement.executeUpdate(sql); }
            lockConnection.commit();
            waiting.get(5, TimeUnit.SECONDS);
            } finally { lockConnection.rollback(); }
        }
        assertEquals(0, mapper.countItemsByReportId(report.getId()));
    }

    @Test
    @Timeout(20)
    void simultaneousSynchronizationProducesOneRow() throws Exception {
        initialActivity();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); service.syncProjectActivities(userA, WEEK); return null; });
            var second = executor.submit(() -> { start.await(); service.syncProjectActivities(userA, WEEK); return null; });
            start.countDown();
            first.get(8, TimeUnit.SECONDS);
            second.get(8, TimeUnit.SECONDS);
        }
        assertEquals(1, service.myFlatItems(USER_A, WEEK).size());
    }

    private void initialActivity() { event(USER_A, TEAM_A, "PROGRESS_CHANGED", "IN_PROGRESS", 10, "2026-11-03T09:00:00", "처음 작업 10%"); }

    private void event(long reportUser, Long reportTeam, String type, String status, int progress, String at, String summary) {
        jdbc.update("INSERT INTO project_task_activity(project_id,task_id,actor_user_id,report_user_id,report_team_id,event_type,summary,project_name,task_title,task_status,progress,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                TASK, TASK, reportUser, reportUser, reportTeam, type, summary, "연계 테스트", "실제 업무", status, progress, LocalDateTime.parse(at));
    }

    private static CustomUserDetails user(long id, Long team, String role) {
        UserAccount user = new UserAccount();
        user.setId(id); user.setUsername("test-" + id); user.setName("테스트 담당자"); user.setTeamId(team); user.setRoles(List.of(role));
        return new CustomUserDetails(user);
    }

    private static Team team(long id) {
        Team team = new Team(); team.setId(id); team.setName("테스트 팀 " + id); team.setWeeklyReportTarget(true); return team;
    }
}
