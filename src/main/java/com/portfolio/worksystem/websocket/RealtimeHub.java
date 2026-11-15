package com.portfolio.worksystem.websocket;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.config.ReleaseFeatures;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@RequiredArgsConstructor
@Component
public class RealtimeHub {
    private final ObjectMapper objectMapper;
    private final ReleaseFeatures releaseFeatures;
    private final Map<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();

    public void register(CustomUserDetails user, WebSocketSession session) {
        userSessions.computeIfAbsent(user.getUserId(), key -> ConcurrentHashMap.newKeySet()).add(session);
        sessionUsers.put(session.getId(), user.getUserId());
        Map<String, Object> connected = releaseFeatures.isPresenceEnabled() ? Map.of(
                "userId", user.getUserId(),
                "onlineUserIds", onlineUserIds()
        ) : Map.of("userId", user.getUserId());
        sendToSession(session, "CONNECTED", connected);
        broadcastPresence();
    }

    public void unregister(WebSocketSession session) {
        Long userId = sessionUsers.remove(session.getId());
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                userSessions.remove(userId);
            }
        }
        broadcastPresence();
    }

    public boolean isOnline(Long userId) {
        Set<WebSocketSession> sessions = userSessions.get(userId);
        return sessions != null && sessions.stream().anyMatch(WebSocketSession::isOpen);
    }

    public List<Long> onlineUserIds() {
        return userSessions.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(WebSocketSession::isOpen))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    public void sendToUser(Long userId, String type, Object payload) {
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        List<WebSocketSession> closed = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            if (!sendToSession(session, type, payload)) {
                closed.add(session);
            }
        }
        closed.forEach(this::unregister);
    }

    public void sendToUsers(Collection<Long> userIds, String type, Object payload) {
        if (userIds == null) {
            return;
        }
        userIds.stream().distinct().forEach(userId -> sendToUser(userId, type, payload));
    }

    public boolean sendToSession(WebSocketSession session, String type, Object payload) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            String body = objectMapper.writeValueAsString(new LiveEvent(type, payload));
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(body));
                }
            }
            return true;
        } catch (JacksonException e) {
            throw new IllegalStateException("실시간 이벤트 직렬화에 실패했습니다.", e);
        } catch (IOException | IllegalStateException e) {
            // session.isOpen() 체크와 sendMessage 사이에 close가 일어나는 race condition (Tomcat 측에서 IllegalStateException 발생).
            // 로그아웃 직후 broadcastPresence가 닫힌 세션에 send 시도하는 케이스. 단순 무시.
            return false;
        }
    }

    private void broadcastPresence() {
        if (!releaseFeatures.isPresenceEnabled()) return;
        sendToUsers(new ArrayList<>(userSessions.keySet()), "PRESENCE_CHANGED", Map.of("onlineUserIds", onlineUserIds()));
    }

    private record LiveEvent(String type, Object payload) {
    }
}
