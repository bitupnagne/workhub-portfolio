package com.portfolio.worksystem.config;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * [BOOT-1] 최초 설치용 슈퍼관리자 생성.
 *
 * 신규 서버는 Flyway가 스키마만 만들 뿐이라 로그인할 계정이 없다. 오프라인 설치기가 문답으로 받은
 * 계정/비밀번호를 환경변수로 넘기면, 기동 직후 계정이 없을 때 한 번 만든다.
 *
 * 왜 SQL이 아니라 앱에서 만드는가:
 *  - 비밀번호를 BCrypt로 해싱해야 하는데 셸에는 bcrypt 도구가 없다(htpasswd/python 등은 폐쇄망에서
 *    보장되지 않는다). 앱은 이미 {@link PasswordEncoder} 빈을 갖고 있어 해시 호환이 확실하다.
 *  - Flyway 완료 이후 시점이 보장된다(ApplicationReadyEvent).
 *
 * 안전장치:
 *  - 환경변수가 비어 있으면 아무 일도 하지 않는다. 기존 운영 서버는 영향을 받지 않는다.
 *  - 같은 username이 이미 있으면 건드리지 않는다. 재기동해도 비밀번호가 되돌아가지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SuperAdminBootstrap {

    /** seed-members.sql의 admin 계정과 동일한 구성. SUPER_ADMIN 단독으로는 시스템관리 화면 일부가 막힌다. */
    private static final List<String> ROLES =
            List.of("SUPER_ADMIN", "SYSTEM_ADMIN", "HR_ADMIN", "ERP_ADMIN", "REPORT_ADMIN");

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.super-admin.username:${SUPER_ADMIN_USERNAME:}}")
    private String username;

    @Value("${app.bootstrap.super-admin.password:${SUPER_ADMIN_PASSWORD:}}")
    private String password;

    @Value("${app.bootstrap.super-admin.name:${SUPER_ADMIN_NAME:관리자}}")
    private String displayName;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void createIfAbsent() {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return;
        }
        String id = username.trim();
        UserAccount existing = userMapper.findByUsername(id);
        if (existing != null) {
            log.info("[초기관리자] '{}' 계정이 이미 있어 건너뜁니다.", id);
            return;
        }
        userMapper.insertSystemAdmin(id, passwordEncoder.encode(password), displayName.trim());

        UserAccount created = userMapper.findByUsername(id);
        if (created == null) {
            // INSERT는 통과했는데 조회가 안 되면 권한을 못 붙인다. 반쪽 계정을 남기지 않도록 롤백시킨다.
            throw new IllegalStateException("초기 관리자 생성 후 조회 실패: " + id);
        }
        for (String role : ROLES) {
            userMapper.addRole(created.getId(), role);
        }
        log.info("[초기관리자] '{}' 생성 완료 (권한 {}). 최초 로그인 후 비밀번호를 변경하세요.", id, ROLES);
    }
}
