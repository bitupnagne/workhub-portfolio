package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.worksystem.dto.ProjectForm;
import com.portfolio.worksystem.dto.ProjectTaskForm;
import com.portfolio.worksystem.model.Project;
import com.portfolio.worksystem.model.ProjectActivity;
import com.portfolio.worksystem.model.ProjectChecklist;
import com.portfolio.worksystem.model.ProjectComment;
import com.portfolio.worksystem.model.ProjectEvidence;
import com.portfolio.worksystem.model.ProjectHandover;
import com.portfolio.worksystem.model.ProjectPerson;
import com.portfolio.worksystem.model.ProjectTask;
import com.portfolio.worksystem.model.ProjectTaskStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

class ProjectTemplatesTest {
    @Test
    void projectListEscapesUserContentAndRetainsCreateFormValues() throws Exception {
        var model = model();
        Project project = (Project) model.get("project");
        project.setName("<script>alert(1)</script>");
        String html = render("list", model);
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>")
                .contains("action=\"/projects\"", "name=\"ownerId\"", "name=\"memberIds\"", "data-project-filter=\"ARCHIVED\"");
    }

    @Test
    void projectDetailRendersBothViewsAndPartitionsTasksByTheirStatus() throws Exception {
        var model = model();
        String html = render("detail", model);
        assertThat(html).contains("data-task-view-panel=\"board\"", "data-task-view-panel=\"list\"",
                "href=\"/projects/1/tasks/2\"", "data-status=\"IN_PROGRESS\"", "평균 진척도")
                .doesNotContain("th:each=", "th:with=");
        assertThat(count(html, "class=\"pm-task-card\"")).isEqualTo(1);
        assertThat(count(html, "class=\"pm-lane\"")).isEqualTo(ProjectTaskStatus.values().length);
    }

    @Test
    void nonContributorsCanReadTaskButReceiveNoMutationForms() throws Exception {
        var model = model();
        model.put("canContributeProject", false);
        model.put("canEditTask", false);
        model.put("canRequestHandover", false);
        String html = render("task", model);
        assertThat(html).contains("업무 내용", "근거 자료", "댓글·작업 메모", "활동 기록")
                .doesNotContain("data-project-form", "name=\"assigneeId\"", "인계 요청 보내기");
    }

    @Test
    void taskDetailsRenderFilesAndHandoverActionsWithTheExistingTaskRoute() throws Exception {
        var model = model();
        var now = LocalDateTime.of(2026, 11, 8, 10, 30);
        var checklist = new ProjectChecklist(); checklist.setId(3L); checklist.setText("API 검증"); checklist.setCompleted(true);
        var comment = new ProjectComment(); comment.setUserName("홍길동"); comment.setBody("검증 완료 <img src=x>"); comment.setCreatedAt(now);
        var file = new ProjectEvidence(); file.setId(4L); file.setKind("FILE"); file.setOriginalFilename("근거.pdf");
        file.setUploadedByName("홍길동"); file.setSizeBytes(1024L); file.setCreatedAt(now);
        var activity = new ProjectActivity(); activity.setActorName("홍길동"); activity.setSummary("진척도를 변경했습니다."); activity.setOccurredAt(now);
        var handover = new ProjectHandover(); handover.setId(5L); handover.setStatus("PENDING");
        handover.setFromUserName("홍길동"); handover.setToUserName("김하나"); handover.setSummary("검토 후 반영"); handover.setRequestedAt(now);
        model.put("checklist", List.of(checklist)); model.put("comments", List.of(comment));
        model.put("evidence", List.of(file)); model.put("activities", List.of(activity)); model.put("handover", handover);
        model.put("canRespondHandover", true); model.put("canCancelHandover", true);
        String html = render("task", model);
        assertThat(html).contains("href=\"/projects/1/files/4\"", "download", "검증 완료 &lt;img src=x&gt;",
                "action=\"/projects/1/tasks/2/handover/5/respond\"", "value=\"ACCEPT\"", "value=\"REJECT\"",
                "action=\"/projects/1/tasks/2/handover/5/cancel\"", "name=\"assigneeId\"", "type=\"hidden\"")
                .doesNotContain("<img src=x>");
    }

    @Test
    void pastHandoverRequestsKeepFullOriginalTextAndStayReadOnly() throws Exception {
        var model = model();
        var requestedAt = LocalDateTime.of(2026, 11, 7, 9, 15);
        var older = new ProjectHandover();
        older.setId(10L); older.setFromUserName("홍길동"); older.setToUserName("김하나");
        older.setRequestedByName("프로젝트 책임자"); older.setStatus("REJECTED");
        older.setSummary("원문 ".repeat(1200) + "인계 내용의 마지막 문장");
        older.setResponse("검토 ".repeat(450) + "반려 의견의 마지막 문장");
        older.setRequestedAt(requestedAt); older.setRespondedAt(requestedAt.plusHours(1));
        var newer = new ProjectHandover();
        newer.setId(11L); newer.setFromUserName("홍길동"); newer.setToUserName("김하나");
        newer.setRequestedByName("홍길동"); newer.setStatus("ACCEPTED");
        newer.setSummary("보완한 설계 문서와 남은 테스트를 전달합니다.");
        newer.setResponse("보완 자료를 확인하고 업무를 인수했습니다.");
        newer.setRequestedAt(requestedAt.plusDays(1)); newer.setRespondedAt(requestedAt.plusDays(1).plusHours(2));
        model.put("handoverHistory", List.of(newer, older));
        model.put("canContributeProject", false); model.put("canEditTask", false); model.put("canRequestHandover", false);

        String html = render("task", model);
        assertThat(html).contains("data-handover-history", older.getSummary(), older.getResponse(), newer.getSummary(), newer.getResponse(),
                "프로젝트 책임자", "인수 반려", "인수 완료", "2026.11.07 09:15", "2026.11.08 11:15",
                "최근 댓글·작업 메모를 최대 200건", "최근 활동을 최대 200건")
                .doesNotContain("data-project-form", "name=\"decision\"", "인계 요청 보내기");
        assertThat(count(html, "class=\"pm-history-entry\"")).isEqualTo(2);
    }

    private Map<String, Object> model() {
        var person = new ProjectPerson();
        person.setUserId(1L); person.setName("홍길동"); person.setTeamName("개발팀");
        var project = new Project();
        project.setId(1L); project.setName("포털 개편"); project.setOwnerId(1L); project.setOwnerName("홍길동");
        project.setStatus("ACTIVE"); project.setProgress(35); project.setTaskCount(1); project.setMemberCount(1);
        var task = new ProjectTask();
        task.setId(2L); task.setProjectId(1L); task.setTitle("로그인 API 작업");
        task.setAssigneeId(1L); task.setAssigneeName("홍길동"); task.setStatus(ProjectTaskStatus.IN_PROGRESS);
        task.setProgress(35); task.setDueDate(LocalDate.of(2030, 12, 31));
        var projectForm = new ProjectForm();
        projectForm.setName(project.getName()); projectForm.setOwnerId(1L); projectForm.setMemberIds(List.of(1L));
        var taskForm = new ProjectTaskForm();
        taskForm.setTitle(task.getTitle()); taskForm.setAssigneeId(1L); taskForm.setStatus(task.getStatus()); taskForm.setProgress(35);
        Map<String, Object> model = new HashMap<>();
        model.put("title", "프로젝트 관리"); model.put("q", ""); model.put("hiddenMenus", Set.of());
        model.put("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-csrf"));
        model.put("projects", List.of(project)); model.put("project", project); model.put("task", task);
        model.put("tasks", List.of(task)); model.put("people", List.of(person)); model.put("members", List.of(person));
        model.put("projectForm", projectForm); model.put("taskForm", taskForm); model.put("taskStatuses", ProjectTaskStatus.values());
        for (String name : List.of("collaborators", "checklist", "activities", "comments", "evidence", "handoverHistory")) model.put(name, List.of());
        for (String name : List.of("canManageProject", "canContributeProject", "canEditTask", "canRequestHandover")) model.put(name, true);
        for (String name : List.of("canRespondHandover", "canCancelHandover")) model.put(name, false);
        return model;
    }

    private String render(String page, Map<String, Object> model) throws Exception {
        String source;
        try (var input = getClass().getResourceAsStream("/templates/projects/" + page + ".html")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 공통 셸은 기존 화면에서 검증하며, 새 페이지와 실제 프로젝트 폼 fragment를 함께 렌더링한다.
        source = source.replaceAll("<div th:replace=\"~\\{fragments/app :: (sidebar|topbar)}\"></div>", "");
        var fragments = new ClassLoaderTemplateResolver();
        fragments.setPrefix("templates/"); fragments.setSuffix(".html"); fragments.setCharacterEncoding("UTF-8");
        fragments.setResolvablePatterns(Set.of("projects/fragments")); fragments.setOrder(1);
        var strings = new StringTemplateResolver(); strings.setOrder(2);
        var engine = new SpringTemplateEngine(); engine.addTemplateResolver(fragments); engine.addTemplateResolver(strings);
        var servlet = new MockServletContext();
        var request = new MockHttpServletRequest(servlet);
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        return engine.process(source, new WebContext(exchange, Locale.KOREAN, model));
    }

    private int count(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }
}
