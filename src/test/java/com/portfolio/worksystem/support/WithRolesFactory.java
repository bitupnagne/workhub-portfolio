package com.portfolio.worksystem.support;

import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

public class WithRolesFactory implements WithSecurityContextFactory<WithRoles> {
    @Override
    public SecurityContext createSecurityContext(WithRoles annotation) {
        UserAccount account = new UserAccount();
        account.setId(7001L);
        account.setUsername("roles-test");
        account.setName("권한 테스트");
        account.setActive(true);
        account.setRoles(List.of(annotation.value()));
        CustomUserDetails principal = new CustomUserDetails(account);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
        return context;
    }
}
