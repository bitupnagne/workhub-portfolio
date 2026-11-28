package com.portfolio.worksystem.security;

import java.util.function.Predicate;

/**
 * [PERM] 메뉴 권한 보유 여부를 세션 principal 쪽에서 물어볼 수 있게 하는 연결점.
 * 관리자 OTP 필수 정책이 '관리자 역할'뿐 아니라 메뉴 권한을 받은 사람에게도 걸려야 한다.
 */
public final class AdminGrantLookup {
    private static volatile Predicate<Long> anyGrant = userId -> false;

    private AdminGrantLookup() {
    }

    public static void register(Predicate<Long> lookup) {
        anyGrant = lookup == null ? userId -> false : lookup;
    }

    public static boolean hasAnyGrant(Long userId) {
        try {
            return userId != null && anyGrant.test(userId);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
