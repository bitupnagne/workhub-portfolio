package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class MessageRoom {
    private Long id;
    private String title;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String memberNames;
    private Integer unreadCount;
    private String lastMessageBody;
    private LocalDateTime lastMessageAt;
    private String customTitle;
    private Boolean pinned;
    private Boolean muted;
    private Integer opacityPercent;
    private List<MessageRoomMember> avatarMembers = new ArrayList<>();
}
