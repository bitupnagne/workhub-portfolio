package com.portfolio.worksystem.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 로그인 실패 잠금(brute-force 방어, P0). 인메모리, 5회 실패 시 10분 잠금. fail-open.
 */
@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_MILLIS = 10 * 60 * 1000L;

    private record Attempt(int count, long lockUntil) {}

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private String key(String loginId) {
        return loginId == null ? "" : loginId.trim().toLowerCase();
    }

    public void recordFailure(String loginId) {
        String k = key(loginId);
        if (k.isEmpty()) {
            return;
        }
        attempts.compute(k, (id, prev) -> {
            int count = (prev == null ? 0 : prev.count()) + 1;
            long lockUntil = count >= MAX_ATTEMPTS ? System.currentTimeMillis() + LOCK_MILLIS : 0L;
            return new Attempt(count, lockUntil);
        });
    }

    public void reset(String loginId) {
        attempts.remove(key(loginId));
    }

    public boolean isBlocked(String loginId) {
        Attempt a = attempts.get(key(loginId));
        if (a == null) {
            return false;
        }
        if (a.lockUntil() > System.currentTimeMillis()) {
            return true;
        }
        if (a.lockUntil() > 0) {
            attempts.remove(key(loginId));
        }
        return false;
    }
}
