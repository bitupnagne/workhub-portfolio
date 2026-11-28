package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class MessageRoomMember {
    private Long roomId;
    private Long userId;
    private String userName;
    private String teamName;
    private String profileImageFilename;
    private Long lastReadMessageId;
    private LocalDateTime createdAt;
    // [O-1] 실시간 presence — 우측 사이드패널과 동일한 의미로 채워진다.
    private boolean online;
    private String statusLabel;
    private String statusClass;
}