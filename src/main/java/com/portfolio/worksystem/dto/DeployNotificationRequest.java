package com.portfolio.worksystem.dto;

public record DeployNotificationRequest(
        Long recipientOfficeUserId,
        Long senderOfficeUserId,
        String type,
        String title,
        String body,
        String linkUrl
) {
}
