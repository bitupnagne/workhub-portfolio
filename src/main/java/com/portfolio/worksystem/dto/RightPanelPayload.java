package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.MessageRoom;
import com.portfolio.worksystem.model.Notification;
import java.util.List;

public record RightPanelPayload(
        Long currentUserId,
        int unreadNotificationCount,
        int unreadMessageCount,
        List<Notification> notifications,
        List<MessageRoom> rooms,
        List<RightPanelGroup> groups,
        List<OrgTreeNode> orgTree
) {
}