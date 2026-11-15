package com.portfolio.worksystem.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.portfolio.worksystem.config.ReleaseFeatures;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class RealtimeHubPresenceTest {
    @ParameterizedTest
    @CsvSource({"0.1,false", "0.2,false", "0.3,false", "0.4,false", "0.5,true", "0.6,true", "0.7,true", "0.8,true", "full,true"})
    void presenceEventsAreEditionGatedWhileOtherRealtimeEventsRemainAvailable(String edition, boolean enabled) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RealtimeHub hub = new RealtimeHub(mapper, new ReleaseFeatures(edition));
        CustomUserDetails first = mock(CustomUserDetails.class);
        CustomUserDetails second = mock(CustomUserDetails.class);
        when(first.getUserId()).thenReturn(41L);
        when(second.getUserId()).thenReturn(42L);
        WebSocketSession firstSession = session("one");
        WebSocketSession secondSession = session("two");
        hub.register(first, firstSession);
        hub.register(second, secondSession);

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(secondSession, atLeastOnce()).sendMessage(messages.capture());
        var connected = messages.getAllValues().stream().map(message -> mapper.readTree(message.getPayload()))
                .filter(event -> "CONNECTED".equals(event.path("type").asText())).findFirst().orElseThrow();
        assertThat(connected.path("payload").path("userId").asLong()).isEqualTo(42L);
        assertThat(connected.path("payload").has("onlineUserIds")).isEqualTo(enabled);
        if (enabled) assertThat(connected.path("payload").path("onlineUserIds").toString()).isEqualTo("[41,42]");
        assertThat(messages.getAllValues().stream().map(message -> mapper.readTree(message.getPayload()))
                .anyMatch(event -> "PRESENCE_CHANGED".equals(event.path("type").asText()))).isEqualTo(enabled);

        clearInvocations(firstSession, secondSession);
        hub.sendToUser(41L, "PANEL_CHANGED", Map.of("reason", "approval"));
        ArgumentCaptor<TextMessage> notification = ArgumentCaptor.forClass(TextMessage.class);
        verify(firstSession).sendMessage(notification.capture());
        assertThat(mapper.readTree(notification.getValue().getPayload()).path("type").asText()).isEqualTo("PANEL_CHANGED");

        clearInvocations(firstSession, secondSession);
        hub.unregister(secondSession);
        if (enabled) {
            ArgumentCaptor<TextMessage> changed = ArgumentCaptor.forClass(TextMessage.class);
            verify(firstSession).sendMessage(changed.capture());
            var event = mapper.readTree(changed.getValue().getPayload());
            assertThat(event.path("type").asText()).isEqualTo("PRESENCE_CHANGED");
            assertThat(event.path("payload").path("onlineUserIds").toString()).isEqualTo("[41]");
        } else {
            verifyNoInteractions(firstSession);
            verify(secondSession, never()).sendMessage(any());
        }
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
