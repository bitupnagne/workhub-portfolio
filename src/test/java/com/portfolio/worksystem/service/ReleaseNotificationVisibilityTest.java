package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.portfolio.worksystem.config.ReleaseFeatures;
import com.portfolio.worksystem.mapper.CommunicationMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.Notification;
import com.portfolio.worksystem.model.EmployeePresence;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.websocket.RealtimeHub;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class ReleaseNotificationVisibilityTest {
    @ParameterizedTest
    @CsvSource({"0.1,false,false", "0.2,false,true", "0.3,false,true", "0.4,false,true", "0.5,true,true", "0.6,true,true", "0.7,true,true", "0.8,true,true", "full,true,true"})
    void onlinePresenceHasItsOwnEditionGateWithoutChangingAttendance(String edition, boolean presence, boolean attendance) {
        RealtimeHub hub = mock(RealtimeHub.class);
        CommunicationService service = new CommunicationService(mock(CommunicationMapper.class), mock(UserMapper.class), hub,
                mock(OrganizationService.class), mock(AnonymityService.class), mock(PresenceCacheService.class), mock(SystemSettingsService.class));
        ReflectionTestUtils.setField(service, "releaseFeatures", new ReleaseFeatures(edition));
        EmployeePresence employee = new EmployeePresence();
        employee.setUserId(42L);
        employee.setOrgGroupName("개발본부");
        employee.setStatus("WORKING");
        employee.setOnline(true); // A cached value must not cross the edition boundary.
        when(hub.isOnline(42L)).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "enrichPresence", employee);

        assertThat(employee.isOnline()).isEqualTo(presence);
        assertThat(employee.getStatusLabel()).isEqualTo(attendance ? "근무" : null);
        assertThat(employee.getStatus()).isEqualTo(attendance ? "WORKING" : null);
        if (presence) verify(hub).isOnline(42L);
        else verifyNoInteractions(hub);
    }

    @Test
    void earlyPanelCountsAndReadsOnlyVisibleAttendanceNotifications() {
        CommunicationMapper mapper = mock(CommunicationMapper.class);
        CommunicationService service = new CommunicationService(mapper, mock(UserMapper.class), mock(RealtimeHub.class),
                mock(OrganizationService.class), mock(AnonymityService.class), mock(PresenceCacheService.class), mock(SystemSettingsService.class));
        ReflectionTestUtils.setField(service, "releaseFeatures", new ReleaseFeatures("0.2"));
        CustomUserDetails user = mock(CustomUserDetails.class);
        when(user.getUserId()).thenReturn(7L);
        Notification attendance = notification(1L, "APPROVAL_REQUEST", "/hr/leave/10/document");
        Notification notice = notification(2L, "ADMIN_NOTICE", "/notices/10");
        Notification message = notification(3L, "MESSAGE", "/dashboard?messageRoomId=1");
        when(mapper.findNotifications(7L)).thenReturn(List.of(attendance, notice, message));

        assertThat(service.notifications(user)).containsExactly(attendance);
        assertThat(service.unreadNotificationCount(user)).isEqualTo(1);
        assertThat(service.unreadMessageCount(user)).isZero();
        verify(mapper, never()).countUnreadMessages(anyLong());
        service.markAllNotificationsRead(user);
        verify(mapper).markNotificationRead(1L, 7L);
        verify(mapper, never()).markNotificationRead(2L, 7L);
        verify(mapper, never()).markNotificationRead(3L, 7L);
        verify(mapper, never()).markAllNotificationsRead(anyLong());
        assertThatThrownBy(() -> service.deleteNotification(user, 2L)).isInstanceOf(BusinessException.class);
        verify(mapper, never()).deleteNotification(2L, 7L);
    }

    @Test
    void personnelEditionNeverQueriesAttendanceNotifications() {
        CommunicationMapper mapper = mock(CommunicationMapper.class);
        CommunicationService service = new CommunicationService(mapper, mock(UserMapper.class), mock(RealtimeHub.class),
                mock(OrganizationService.class), mock(AnonymityService.class), mock(PresenceCacheService.class), mock(SystemSettingsService.class));
        ReflectionTestUtils.setField(service, "releaseFeatures", new ReleaseFeatures("0.1"));
        CustomUserDetails user = mock(CustomUserDetails.class);
        assertThat(service.notifications(user)).isEmpty();
        assertThat(service.unreadNotificationCount(user)).isZero();
        verifyNoInteractions(mapper);
    }

    @Test
    void disabledMessagingDoesNotRunAttachmentCleanup() {
        CommunicationMapper mapper = mock(CommunicationMapper.class);
        CommunicationService service = new CommunicationService(mapper, mock(UserMapper.class), mock(RealtimeHub.class),
                mock(OrganizationService.class), mock(AnonymityService.class), mock(PresenceCacheService.class), mock(SystemSettingsService.class));
        ReflectionTestUtils.setField(service, "releaseFeatures", new ReleaseFeatures("0.4"));
        service.cleanupExpiredMessageAttachments();
        verifyNoInteractions(mapper);
    }

    private Notification notification(long id, String type, String link) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setType(type);
        notification.setLinkUrl(link);
        return notification;
    }
}
