package com.portfolio.worksystem.dto;

public record DesktopUserPayload(
        Long userId,
        String username,
        String name,
        String teamName,
        String dept,
        String jobTitle,
        String email,
        String mobileNumber,
        String extensionNumber,
        String profileImageFilename,
        boolean mustChangePassword
) {
}
