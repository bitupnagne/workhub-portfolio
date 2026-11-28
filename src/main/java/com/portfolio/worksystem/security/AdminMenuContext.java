package com.portfolio.worksystem.security;

import com.portfolio.worksystem.model.AdminMenu;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * [PERM] 지금 요청이 어떤 관리자 메뉴 권한으로 들어왔는지.
 *
 * <p>메뉴 권한만 받은 사람(예: '연차 관리'만 받은 사람)은 기존 역할(HR_ADMIN)이 없다. 그 메뉴 화면 안의 기존 검사
 * (canManageHr 등)가 그 사람을 막지 않도록, 메뉴가 허용한 요청 <b>안에서만</b> 그 메뉴의 역할을 가진 것으로 본다.
 * 다른 화면에서는 아니다 — 권한이 메뉴 밖으로 새지 않는다.
 */
public final class AdminMenuContext {
    private static final String MENU = AdminMenuContext.class.getName() + ".menu";
    private static final String USER = AdminMenuContext.class.getName() + ".user";

    private AdminMenuContext() {
    }

    public static void set(AdminMenu menu, Long userId) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(MENU, menu, RequestAttributes.SCOPE_REQUEST);
            attributes.setAttribute(USER, userId, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** 이 사람이 지금 요청에서 그 역할을 메뉴 권한으로 갖는지. */
    public static boolean implies(Long userId, String role) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null || userId == null) {
            return false;
        }
        Object menu = attributes.getAttribute(MENU, RequestAttributes.SCOPE_REQUEST);
        Object user = attributes.getAttribute(USER, RequestAttributes.SCOPE_REQUEST);
        return menu instanceof AdminMenu adminMenu && userId.equals(user) && adminMenu.getImpliedRoles().contains(role);
    }
}
