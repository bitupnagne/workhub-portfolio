package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class NoticeComment {
    private Long id;
    private Long noticeId;
    private Long parentId;
    private String body;
    private boolean anonymous;
    private Long authorUserId;
    private String authorToken;
    private String authorName;
    private String authorProfileImage;
    private String authorTeamName;
    private String authorDept;
    private String authorJobTitle;
    private String authorExtensionNumber;
    private String authorMobileNumber;
    private String authorEmail;
    private LocalDateTime createdAt;
    private List<NoticeComment> replies = new ArrayList<>();
}
