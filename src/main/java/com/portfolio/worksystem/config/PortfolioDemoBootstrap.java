package com.portfolio.worksystem.config;

import com.portfolio.worksystem.dto.ProjectForm;
import com.portfolio.worksystem.dto.ProjectTaskForm;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.ProjectTaskStatus;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.ProjectService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seed the isolated public database once, through the same project workflows as the UI. */
@Component
@RequiredArgsConstructor
public class PortfolioDemoBootstrap implements ApplicationRunner {
    private static final String MARKER = "portfolio.public-demo.project-seeded";
    private final JdbcTemplate jdbc;
    private final UserMapper users;
    private final ProjectService projects;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;

    @Value("${app.portfolio.public-demo:${PORTFOLIO_PUBLIC_DEMO:false}}")
    private boolean publicDemo;

    @Value("${app.portfolio.demo-password:${PORTFOLIO_DEMO_PASSWORD:}}")
    private String demoPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (!publicDemo) return;
        if (!"workhub_demo".equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) {
            throw new IllegalStateException("공개 데모는 별도 workhub_demo 데이터베이스에서만 실행할 수 있습니다.");
        }
        if (demoPassword == null || demoPassword.isBlank() || demoPassword.length() < 12) {
            throw new IllegalStateException("공개 데모 비밀번호는 외부 설정으로 12자 이상 제공해 주세요.");
        }
        // Only the disabled, synthetic seed identities are activated; existing credentials are preserved.
        jdbc.update("UPDATE users SET password=? WHERE password=? AND username LIKE 'demo.%'",
                passwords.encode(demoPassword), "!DEMO_LOGIN_DISABLED");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_settings WHERE setting_key = ?", Integer.class, MARKER) > 0) return;
        CustomUserDetails admin = principal("demo.admin");
        CustomUserDetails employee = principal("demo.user102");
        Long leaderId = principal("demo.user101").getUserId();
        Long colleagueId = principal("demo.user103").getUserId();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        ProjectForm form = new ProjectForm();
        form.setName("팀 업무 공유 개선 프로젝트");
        form.setDescription("가상의 팀이 담당 업무, 진행 상황, 근거 자료와 인수인계를 공유하는 공개 데모 프로젝트입니다. 작업 활동은 해당 주 주간보고에 자동으로 모입니다.");
        form.setOwnerId(admin.getUserId());
        form.setStartDate(today.minusDays(7));
        form.setDueDate(today.plusDays(21));
        form.setMemberIds(List.of(admin.getUserId(), leaderId, employee.getUserId(), colleagueId));
        Long project = projects.createProject(admin, form);
        task(admin, project, "프로젝트 요구사항 정리", leaderId, ProjectTaskStatus.DONE, 100, today);
        Long ongoing = task(admin, project, "주간보고 자동 반영 확인", employee.getUserId(), ProjectTaskStatus.IN_PROGRESS, 65, today.plusDays(3));
        projects.addChecklist(admin, project, ongoing, "프로젝트 활동과 주간보고 초안을 함께 확인하기");
        projects.addChecklist(admin, project, ongoing, "담당 업무의 진척도와 근거 자료 기록하기");
        projects.addComment(employee, project, ongoing, "담당 업무의 진행 상황을 갱신했습니다. 주간보고 화면에서 이번 주 활동을 확인할 수 있습니다.");
        projects.addLink(admin, project, ongoing, "공개 기술 참고 문서", "https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html");
        Long handover = task(admin, project, "업무 인수인계 준비", employee.getUserId(), ProjectTaskStatus.TODO, 0, today.plusDays(7));
        projects.requestHandover(admin, project, handover, colleagueId, "진행 방법과 참고 자료를 확인한 뒤 인수 수락을 눌러 담당자를 변경해 주세요.");
        task(admin, project, "화면 사용성 검토", admin.getUserId(), ProjectTaskStatus.REVIEW, 90, today.plusDays(5));
        jdbc.update("INSERT INTO app_settings(setting_key, setting_value) VALUES (?, '1')", MARKER);
    }

    private CustomUserDetails principal(String username) {
        UserAccount account = users.findByUsername(username);
        if (account == null || !Boolean.TRUE.equals(account.getActive())) {
            throw new IllegalStateException("공개 데모의 가상 계정을 확인할 수 없습니다.");
        }
        account.setRoles(users.findRolesByUserId(account.getId()));
        return new CustomUserDetails(account);
    }

    private Long task(CustomUserDetails actor, Long project, String title, Long assignee,
                      ProjectTaskStatus status, int progress, LocalDate due) {
        ProjectTaskForm form = new ProjectTaskForm();
        form.setTitle(title);
        form.setDescription("포트폴리오의 가상 업무입니다. 상태·담당자·진척도와 작업 기록을 수정해 보세요.");
        form.setAssigneeId(assignee);
        form.setStatus(status);
        form.setProgress(progress);
        form.setDueDate(due);
        return projects.createTask(actor, project, form);
    }
}
