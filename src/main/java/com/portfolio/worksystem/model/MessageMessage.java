package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class MessageMessage {
    private Long id;
    private Long roomId;
    private Long senderUserId;
    private String senderName;
    private String senderProfileImageFilename;
    private String body;
    private LocalDateTime createdAt;
    private List<MessageAttachment> attachments = new ArrayList<>();
}
