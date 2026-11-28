package com.portfolio.worksystem.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * [PERM] 역할을 가진 <b>CustomUserDetails</b> 로 로그인한 상태. 관리자 메뉴 인가가 principal 의 사용자 id 와
 * 메뉴 권한을 보므로, 스프링 기본 {@code @WithMockUser}(User principal)로는 판정이 되지 않는다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@WithSecurityContext(factory = WithRolesFactory.class)
public @interface WithRoles {
    String[] value();
}
