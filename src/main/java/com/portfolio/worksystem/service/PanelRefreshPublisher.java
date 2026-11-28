package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.websocket.RealtimeHub;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RequiredArgsConstructor
@Service
public class PanelRefreshPublisher {
    private final UserMapper userMapper;
    private final RealtimeHub realtimeHub;
    private final PresenceCacheService presenceCacheService;

    public void publishAllActive(String reason) {
        List<Long> userIds = userMapper.findAllActive().stream()
                .map(UserAccount::getId)
                .toList();
        publish(userIds, reason);
    }

    /**
     * [O-11] 프로필 사진만 변경됐을 때 전송한다. 트리 전체 refresh를 유발하지 않고
     * 해당 user의 사진만 클라이언트가 in-place로 갱신한다.
     */
    public void publishProfileChanged(Long userId, String profileImageFilename) {
        List<Long> userIds = userMapper.findAllActive().stream()
                .map(UserAccount::getId)
                .toList();
        afterCommit(() -> {
            // [S-4] 사진 변경도 presence 응답에 포함되므로 캐시 evict
            presenceCacheService.evictPresence();
            realtimeHub.sendToUsers(userIds, "PROFILE_CHANGED",
                    Map.of("userId", userId,
                            "profileImageFilename", profileImageFilename == null ? "" : profileImageFilename));
        });
    }

    public void publish(Collection<Long> userIds, String reason) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        String safeReason = reason == null ? "refresh" : reason;
        afterCommit(() -> {
            // [S-4] 변경 이벤트가 발생하면 presence/orgTree 캐시를 즉시 무효화한다.
            // 다음 요청부터 fresh 데이터로 갱신되어 stale 영향 시간 최소화.
            evictCachesForReason(safeReason);
            realtimeHub.sendToUsers(userIds, "PANEL_CHANGED", Map.of("reason", safeReason));
        });
    }

    private void evictCachesForReason(String reason) {
        // 직원/팀 변경은 트리·presence 모두 영향. 그 외는 presence만.
        if (reason != null && (reason.startsWith("user-") || reason.startsWith("team-")
                || reason.equals("organization") || reason.equals("admin-notice"))) {
            presenceCacheService.evictAll();
        } else {
            presenceCacheService.evictPresence();
        }
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }
}
