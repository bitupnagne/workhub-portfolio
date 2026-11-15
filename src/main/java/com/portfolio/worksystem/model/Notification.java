package com.portfolio.worksystem.model;

import com.portfolio.worksystem.util.NoticeContentUtils;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Notification {
    private Long id;
    private Long recipientUserId;
    private Long senderUserId;
    private String senderName;
    private String type;
    private String title;
    private String body;
    private String linkUrl;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    private LocalDateTime pollStartDate;
    private LocalDateTime pollEndDate;

    public boolean isUnread() {
        return readAt == null;
    }

    public String getPlainBody() {
        return NoticeContentUtils.toPlainText(body);
    }

    public boolean isHasPoll() {
        return pollEndDate != null;
    }

    /** 투표 진행중: 시작~종료 사이 (시작일이 없으면 종료 전까지 진행중) */
    public boolean isPollOpen() {
        if (pollEndDate == null) return false;
        LocalDateTime now = LocalDateTime.now();
        boolean started = pollStartDate == null || !now.isBefore(pollStartDate);
        return started && !now.isAfter(pollEndDate);
    }

    /** 투표 종료: 종료일 경과 */
    public boolean isPollClosed() {
        return pollEndDate != null && LocalDateTime.now().isAfter(pollEndDate);
    }
}
