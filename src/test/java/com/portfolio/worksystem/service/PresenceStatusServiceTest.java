package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.websocket.RealtimeHub;
import java.util.List;
import org.junit.jupiter.api.Test;

/** [PR] 내 접속 상태 — 온라인/자리비움만, 바꾸면 저장 + 웹소켓 알림. */
class PresenceStatusServiceTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RealtimeHub hub = mock(RealtimeHub.class);
    private final PresenceCacheService cache = mock(PresenceCacheService.class);
    private final PresenceStatusService service = new PresenceStatusService(users, hub, cache);

    @Test
    void awayIsSavedAndBroadcast() {
        assertThat(service.change(7L, "away")).isEqualTo("AWAY");
        verify(users).updatePresenceAway(7L, true);
        verify(cache).evictPresence();
        verify(hub).setAway(7L, true);
    }

    @Test
    void onlineClearsAway() {
        assertThat(service.change(7L, "ONLINE")).isEqualTo("ONLINE");
        verify(users).updatePresenceAway(7L, false);
        verify(hub).setAway(7L, false);
    }

    @Test
    void otherStatusesAreRejected() {
        assertThatThrownBy(() -> service.change(7L, "OUTSIDE")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.change(7L, null)).isInstanceOf(BusinessException.class);
        verify(users, never()).updatePresenceAway(anyLong(), anyBoolean());
    }

    @Test
    void savedAwayIsRestoredOnStartup() {
        when(users.findPresenceAwayUserIds()).thenReturn(List.of(3L, 9L));
        service.load();
        verify(hub).loadAway(List.of(3L, 9L));
    }
}
