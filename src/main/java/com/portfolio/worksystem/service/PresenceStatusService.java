package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.websocket.RealtimeHub;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [PR] 본인이 고르는 접속 상태 — 온라인(기본) / 자리비움.
 *
 * <p>근태 상태(근무·외근·출장…)와는 따로 간다. 접속(웹소켓) 중일 때만 보이고, 바꾸면 웹소켓으로
 * 모든 접속자에게 바로 알린다(웹 우측 패널·윈도우 앱 모두 새로고침 없이 갱신). 서버를 다시 띄워도 유지된다.
 */
@Service
@RequiredArgsConstructor
public class PresenceStatusService {
    public static final String ONLINE = "ONLINE";
    public static final String AWAY = "AWAY";

    private final UserMapper userMapper;
    private final RealtimeHub realtimeHub;
    private final PresenceCacheService presenceCacheService;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        realtimeHub.loadAway(userMapper.findPresenceAwayUserIds());
    }

    /** @param status ONLINE 또는 AWAY */
    @Transactional
    public String change(Long userId, String status) {
        boolean away;
        if (AWAY.equalsIgnoreCase(status)) {
            away = true;
        } else if (ONLINE.equalsIgnoreCase(status)) {
            away = false;
        } else {
            throw new BusinessException("상태는 온라인 또는 자리비움만 고를 수 있습니다.");
        }
        userMapper.updatePresenceAway(userId, away);
        presenceCacheService.evictPresence();
        realtimeHub.setAway(userId, away);
        return away ? AWAY : ONLINE;
    }

    public String current(Long userId) {
        return realtimeHub.isAway(userId) ? AWAY : ONLINE;
    }
}
