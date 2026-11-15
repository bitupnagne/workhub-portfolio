package com.portfolio.worksystem.websocket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.portfolio.worksystem.dto.MessageForm;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.CommunicationService;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@RequiredArgsConstructor
@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {
    private final RealtimeHub realtimeHub;
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;
    private final CommunicationService communicationService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        CustomUserDetails user = currentUser(session);
        if (user == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized"));
            return;
        }
        session.getAttributes().put("currentUser", user);
        realtimeHub.register(user, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        CustomUserDetails user = currentUser(session);
        if (user == null) {
            realtimeHub.sendToSession(session, "ERROR", Map.of("message", "로그인이 필요합니다."));
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            JsonNode payload = root.path("payload");
            if ("SEND_MESSAGE".equals(type)) {
                if (!releaseFeatures.isMessagingEnabled()) throw new BusinessException("이 버전에서는 메시지를 사용할 수 없습니다.");
                Long roomId = payload.path("roomId").isMissingNode() ? null : payload.path("roomId").asLong();
                String body = payload.path("body").asText("");
                communicationService.sendMessage(user, roomId, new MessageForm(body));
                realtimeHub.sendToSession(session, "MESSAGE_ACCEPTED", Map.of("roomId", roomId));
            }
        } catch (BusinessException e) {
            realtimeHub.sendToSession(session, "ERROR", Map.of("message", e.getMessage()));
        } catch (Exception e) {
            realtimeHub.sendToSession(session, "ERROR", Map.of("message", "실시간 메시지 처리에 실패했습니다."));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        realtimeHub.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        realtimeHub.unregister(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private CustomUserDetails currentUser(WebSocketSession session) {
        Object saved = session.getAttributes().get("currentUser");
        if (saved instanceof CustomUserDetails user) {
            return user;
        }
        Principal principal = session.getPrincipal();
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CustomUserDetails user) {
            return user;
        }
        return null;
    }
}