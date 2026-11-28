package com.portfolio.worksystem.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 신규 설치 시 슈퍼관리자 자동 생성 검증 (빈 DB → Flyway → 부트스트랩).
 *
 * 회귀 검출 시나리오:
 *  - 비밀번호를 평문으로 넣으면 로그인이 안 된다(인코더 경유 여부).
 *  - SUPER_ADMIN만 붙이면 시스템관리 화면 일부가 막힌다(권한 5종 확인).
 *  - 재기동마다 계정을 덮어쓰면 운영자가 바꾼 비밀번호가 되돌아간다(멱등성).
 *  - team_id NOT NULL 제약이나 must_change_password 기본값이 바뀌면 설치 직후 로그인이 막힌다.
 *
 * Docker가 실행 중이어야 한다 (testcontainers).
 */
@Testcontainers
@SpringBootTest(properties = {
        "app.bootstrap.super-admin.username=portfolio-admin",
        "app.bootstrap.super-admin.name=관리자"
})
class SuperAdminBootstrapTest {
    private static final String BOOTSTRAP_PASSWORD = java.util.UUID.randomUUID().toString();

    @Container
    static final MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("worksystem")
            .withUsername("worksystem")
            .withPassword(java.util.UUID.randomUUID().toString());

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("app.bootstrap.super-admin.password", () -> BOOTSTRAP_PASSWORD);
        registry.add("app.anonymity.secret", () -> java.util.UUID.randomUUID().toString());
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
    }

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SuperAdminBootstrap bootstrap;

    @Test
    void 빈_DB에_슈퍼관리자가_생성되고_설정한_비밀번호로_인증된다() {
        UserAccount admin = userMapper.findByUsername("portfolio-admin");

        assertNotNull(admin, "기동 시 슈퍼관리자가 만들어져야 한다");
        assertEquals("관리자", admin.getName());
        assertTrue(passwordEncoder.matches(BOOTSTRAP_PASSWORD, admin.getPassword()),
                "설정한 비밀번호로 인증되어야 한다(평문 저장이면 실패)");
        assertFalse(BOOTSTRAP_PASSWORD.equals(admin.getPassword()), "비밀번호가 평문으로 저장되면 안 된다");
    }

    @Test
    void 시스템관리에_필요한_권한이_모두_붙는다() {
        UserAccount admin = userMapper.findByUsername("portfolio-admin");
        List<String> roles = userMapper.findRolesByUserId(admin.getId());

        assertTrue(roles.containsAll(
                        List.of("SUPER_ADMIN", "SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN", "REPORT_ADMIN")),
                "권한 5종이 모두 있어야 한다. 실제: " + roles);
    }

    @Test
    void 조직에_속하지_않는_활성_시스템계정이다() {
        UserAccount admin = userMapper.findByUsername("portfolio-admin");

        assertEquals(Boolean.TRUE, admin.getActive(), "비활성이면 로그인할 수 없다");
        assertEquals(Boolean.TRUE, admin.getSystemAccount(), "조직도에 노출되지 않는 시스템 계정이어야 한다");
        assertEquals(null, admin.getTeamId(), "시스템 계정은 조직에 속하지 않는다");
        assertEquals(Boolean.FALSE, admin.getMustChangePassword(),
                "설치 시 운영자가 직접 정한 비밀번호이므로 강제 변경을 걸지 않는다");
    }

    @Test
    void 재실행해도_비밀번호를_되돌리지_않는다() {
        UserAccount before = userMapper.findByUsername("portfolio-admin");
        // 운영자가 로그인 후 비밀번호를 바꾼 상황
        userMapper.updatePassword(before.getId(), passwordEncoder.encode("operator-changed-pw"));

        bootstrap.createIfAbsent();

        UserAccount after = userMapper.findByUsername("portfolio-admin");
        assertTrue(passwordEncoder.matches("operator-changed-pw", after.getPassword()),
                "이미 있는 계정은 건드리지 않아야 한다");
        assertEquals(before.getId(), after.getId(), "계정을 새로 만들면 안 된다");
    }
}
