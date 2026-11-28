package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WorkMemoRecipient {
    private Long id;
    private Long memoId;
    private Long recipientUserId;
    private String recipientName;
    private LocalDateTime readAt;
    private boolean starred;
    private LocalDateTime createdAt;
}
