package com.portfolio.worksystem.config;

import static org.assertj.core.api.Assertions.assertThat;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.ReportService;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.portfolio.public-demo=true", "app.log.dir=target/test-public-demo-logs",
                "app.portfolio.storage-max-bytes=4096"})
class PortfolioDemoBootstrapIntegrationTest {
    private static final String DEMO_PASSWORD = java.util.UUID.randomUUID().toString();
    @TempDir static Path storageRoot;
    @Container static final MariaDBContainer<?> DATABASE = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("workhub_demo").withUsername("workhub_demo").withPassword(java.util.UUID.randomUUID().toString());

    @DynamicPropertySource static void database(DynamicPropertyRegistry values) {
        values.add("app.portfolio.demo-password", () -> DEMO_PASSWORD);
        values.add("app.anonymity.secret", () -> java.util.UUID.randomUUID().toString());
        values.add("spring.datasource.url", DATABASE::getJdbcUrl);
        values.add("spring.datasource.username", DATABASE::getUsername);
        values.add("spring.datasource.password", DATABASE::getPassword);
        values.add("app.portfolio.storage-root", () -> storageRoot.toString());
        values.add("app.upload.project-attachment-dir", () -> storageRoot.resolve("projects").toString());
        for (String property : PortfolioDemoStorageLayout.UPLOAD_PROPERTIES) {
            if (!property.equals("app.upload.project-attachment-dir")) {
                values.add(property, () -> storageRoot.resolve(property.substring(property.lastIndexOf('.') + 1)).toString());
            }
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PortfolioDemoBootstrap bootstrap;
    @Autowired ReportService reports;
    @Autowired UserMapper users;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Value("${local.server.port}") int port;

    @Test void emptyIsolatedDatabaseSeedsOnceAndProjectActivitiesReachCurrentWeeklyReport() {
        assertThat(passwords.matches(DEMO_PASSWORD, users.findByUsername("demo.admin").getPassword())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_tasks", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_task_handovers WHERE status='PENDING'", Integer.class)).isEqualTo(1);
        String savedHash = users.findByUsername("demo.admin").getPassword();
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(savedHash.equals(users.findByUsername("demo.admin").getPassword())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects", Integer.class)).isEqualTo(1);

        var employee = users.findByUsername("demo.user102");
        employee.setRoles(users.findRolesByUserId(employee.getId()));
        String week = reports.defaultWeekKey();
        reports.syncProjectActivities(new CustomUserDetails(employee), week);
        assertThat(reports.myFlatItems(employee.getId(), week)).anySatisfy(item -> {
            assertThat(item.getSourceTaskId()).isNotNull();
            assertThat(item.getContent()).contains("주간보고 자동 반영 확인");
        });
    }

    @Test void actualHttpUploadAllowsProjectEvidenceAndRejectsOverBudgetBeforeParsing() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var oversized = client.send(HttpRequest.newBuilder(uri("/projects/1/tasks/1/files"))
                            .header("Content-Type", "multipart/form-data; boundary=demo")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[4097])).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(oversized.statusCode()).isEqualTo(413);
            assertThat(oversized.body()).contains("저장 공간이 가득");

            var loginPage = client.send(HttpRequest.newBuilder(uri("/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
            String loginBody = "username=demo.admin&password=" + URLEncoder.encode(DEMO_PASSWORD, StandardCharsets.UTF_8) + "&_csrf="
                    + URLEncoder.encode(csrf(loginPage.body()), StandardCharsets.UTF_8);
            var login = client.send(HttpRequest.newBuilder(uri("/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(loginBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(302);
            Long project = jdbc.queryForObject("SELECT MIN(id) FROM projects", Long.class);
            Long task = jdbc.queryForObject("SELECT MIN(id) FROM project_tasks WHERE project_id=?", Long.class, project);
            String taskPath = "/projects/" + project + "/tasks/" + task;
            var page = client.send(HttpRequest.newBuilder(uri(taskPath)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            String payload = "--demo\r\nContent-Disposition: form-data; name=\"file\"; filename=\"evidence.txt\"\r\n"
                    + "Content-Type: text/plain\r\n\r\npublic demo evidence\r\n--demo--\r\n";
            var upload = client.send(HttpRequest.newBuilder(uri(taskPath + "/files"))
                            .header("Content-Type", "multipart/form-data; boundary=demo")
                            .header("X-CSRF-TOKEN", csrf(page.body()))
                            .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(upload.statusCode()).isEqualTo(302);
            try (var stored = Files.list(storageRoot.resolve("projects"))) {
                var files = stored.filter(Files::isRegularFile).toList();
                assertThat(files).hasSize(1);
                assertThat(Files.readString(files.getFirst())).isEqualTo("public demo evidence");
            }
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String csrf(String html) {
        var match = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertThat(match.find()).as("CSRF token in rendered form").isTrue();
        return match.group(1);
    }
}
