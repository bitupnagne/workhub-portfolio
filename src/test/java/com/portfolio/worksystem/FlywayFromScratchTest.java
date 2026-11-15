package com.portfolio.worksystem;

import com.portfolio.worksystem.mapper.DesktopAuthMapper;
import com.portfolio.worksystem.model.DesktopDeviceToken;
import com.portfolio.worksystem.model.DesktopSsoToken;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 빈 MariaDB에 V1~Vn 마이그레이션을 끝까지 적용할 수 있는지 검증.
 *
 * 회귀 검출 시나리오:
 *  - 시드 데이터를 전제로 한 INSERT/UPDATE/DELETE를 새 마이그레이션이 도입하면 빈 DB에서 실패한다.
 *    (예: V26 hr_org_leader_alignment — FK 참조 user_id가 사전에 존재해야 INSERT 성공)
 *  - 신규 환경 셋업(dump 이관 없이 java -jar만 띄움) 시 부팅 실패의 원인을 사전에 잡는다.
 *
 * Docker가 실행 중이어야 한다 (testcontainers). docker 미가용 시 자동 skip되지 않으므로,
 * 로컬 docker 미설치 환경에서는 -DskipITs 또는 maven-surefire-plugin 분리 권장.
 */
@Testcontainers
class FlywayFromScratchTest {

    @Container
    static final MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("worksystem")
            .withUsername("worksystem")
            .withPassword(java.util.UUID.randomUUID().toString());

    @Test
    void migrateAllToHead() throws Exception {
        Flyway.configure()
                .dataSource(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword())
                .locations("classpath:db/migration")
                .target("85")
                .load().migrate();
        long userId;
        try (var connection = DriverManager.getConnection(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
             var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users(username, password, name) VALUES ('otp-mapper-test', 'encoded', 'test')");
            try (var result = sql.executeQuery("SELECT LAST_INSERT_ID()")) {
                assertTrue(result.next());
                userId = result.getLong(1);
            }
            // 이전 버전에서 생성된 토큰은 새 컬럼 도입 후에도 인증 완료로 간주해서는 안 된다.
            sql.executeUpdate("INSERT INTO desktop_sso_tokens(user_id, token_hash, redirect_path, expires_at) VALUES ("
                    + userId + ", '" + "a".repeat(64) + "', '/dashboard', DATE_ADD(NOW(), INTERVAL 1 DAY))");
            sql.executeUpdate("INSERT INTO user_roles(user_id, role_code) VALUES (" + userId + ", 'EVAL_ADMIN')");
            sql.executeUpdate("INSERT INTO app_settings(setting_key, setting_value) VALUES "
                    + "('menu.ext.show.eval', 'true'), ('menu.int.show.eval.kpi', 'true')");
            sql.executeUpdate("INSERT INTO notifications(recipient_user_id, type, title, link_url) VALUES "
                    + "(" + userId + ", 'EVAL_KPI', 'retired-module-test', '/eval/kpi?year=2026'),"
                    + "(" + userId + ", 'APPROVAL_REQUEST', 'retained-workflow-test', '/hr/leave/1/document')");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword())
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        assertTrue(result.success,
                "Flyway migrate failed. successfulMigrations="
                        + result.migrationsExecuted + ", warnings=" + result.warnings);

        var info = flyway.info().applied();
        long failed = java.util.Arrays.stream(info).filter(i -> !i.getState().isApplied()).count();
        assertEquals(0, failed,
                "Some migrations did not end up applied. See flyway.info() for details.");
        verifyPortfolioScope();
        verifyDemoAdminVisibility();
        verifySeedCredentialsAreDisabled();
        verifySsoOtpMapperRoundTrip(userId);
    }

    private void verifyPortfolioScope() throws Exception {
        try (var connection = DriverManager.getConnection(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
             var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND (TABLE_NAME REGEXP '^eval_' OR TABLE_NAME IN ('performance_reviews', 'personnel_transfers', 'training_completions'))")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM roles WHERE code = 'EVAL_ADMIN'")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND ((TABLE_NAME = 'emp_grades' AND COLUMN_NAME = 'eval_group') "
                    + "OR (TABLE_NAME = 'teams' AND COLUMN_NAME = 'kpi_target'))")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM emp_grades WHERE active = TRUE")) {
                assertTrue(row.next());
                assertTrue(row.getInt(1) > 0, "Shared grades must remain for employee records and leave allowances");
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM user_roles WHERE role_code = 'EVAL_ADMIN'")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM app_settings WHERE setting_key IN "
                    + "('menu.ext.show.eval', 'menu.int.show.eval.kpi')")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM notifications WHERE title = 'retired-module-test'")) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM notifications WHERE title = 'retained-workflow-test'")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
        }
    }

    private void verifySeedCredentialsAreDisabled() throws Exception {
        try (var connection = DriverManager.getConnection(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
             var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT COUNT(*) FROM users WHERE username LIKE 'demo.%' AND password='!DEMO_LOGIN_DISABLED'")) {
            assertTrue(row.next());
            assertEquals(28, row.getInt(1));
        }
    }

    private void verifyDemoAdminVisibility() throws Exception {
        try (var connection = DriverManager.getConnection(mariadb.getJdbcUrl(), mariadb.getUsername(), mariadb.getPassword());
             var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT username, system_account, team_id FROM users WHERE id = 99")) {
            assertTrue(row.next());
            assertEquals("demo.admin", row.getString("username"));
            assertTrue(row.getBoolean("system_account"));
            assertEquals(null, row.getObject("team_id"));
        }
    }

    private void verifySsoOtpMapperRoundTrip(long userId) throws Exception {
        var datasource = new UnpooledDataSource("org.mariadb.jdbc.Driver", mariadb.getJdbcUrl(),
                mariadb.getUsername(), mariadb.getPassword());
        var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), datasource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAlias("DesktopDeviceToken", DesktopDeviceToken.class);
        configuration.getTypeAliasRegistry().registerAlias("DesktopSsoToken", DesktopSsoToken.class);
        try (var xml = getClass().getResourceAsStream("/mapper/DesktopAuthMapper.xml")) {
            new XMLMapperBuilder(xml, configuration, "mapper/DesktopAuthMapper.xml", configuration.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            DesktopAuthMapper mapper = session.getMapper(DesktopAuthMapper.class);
            LocalDateTime now = LocalDateTime.now();
            assertEquals(false, mapper.findUsableSsoToken("a".repeat(64), now).isOtpVerified());
            for (boolean verified : new boolean[] {true, false}) {
                var token = new DesktopSsoToken();
                token.setUserId(userId);
                token.setTokenHash((verified ? "b" : "c").repeat(64));
                token.setRedirectPath("/deploy-system/sso");
                token.setExpiresAt(now.plusMinutes(2));
                token.setOtpVerified(verified);
                mapper.insertSsoToken(token);
                var restored = mapper.findUsableSsoToken(token.getTokenHash(), now);
                assertEquals(verified, restored.isOtpVerified());
                assertEquals(1, mapper.markSsoTokenUsed(restored.getId(), now));
                assertEquals(0, mapper.markSsoTokenUsed(restored.getId(), now));
                assertEquals(null, mapper.findUsableSsoToken(token.getTokenHash(), now));
            }
            var expired = new DesktopSsoToken();
            expired.setUserId(userId);
            expired.setTokenHash("d".repeat(64));
            expired.setRedirectPath("/dashboard");
            expired.setExpiresAt(now.minusMinutes(1));
            expired.setOtpVerified(true);
            mapper.insertSsoToken(expired);
            assertEquals(null, mapper.findUsableSsoToken(expired.getTokenHash(), now));
            assertEquals(0, mapper.markSsoTokenUsed(expired.getId(), now));
        }
    }
}
