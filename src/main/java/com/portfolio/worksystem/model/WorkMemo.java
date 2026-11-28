package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 업무전달 — 장기 보관·검색용 메시지. 채팅과 별개. */
@Data
public class WorkMemo {
    private Long id;
    private Long senderUserId;
    private String senderName;
    private String senderProfileImageFilename;
    private String title;
    private String body;
    private String importance; // NORMAL or IMPORTANT
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<WorkMemoAttachment> attachments = new ArrayList<>();
    // 조회 시 채워지는 필드 (받은함 기준)
    private LocalDateTime readAt;
    private Boolean starred;
    private Integer recipientCount; // 보낸함 기준 N명에게 전달
    private Integer unreadRecipientCount;

    public boolean isUnread() { return readAt == null; }
    public boolean isImportant() { return "IMPORTANT".equalsIgnoreCase(importance); }
    public boolean isStarred() { return Boolean.TRUE.equals(starred); }
    public boolean hasAttachments() { return attachments != null && !attachments.isEmpty(); }
}
