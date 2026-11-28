package com.portfolio.worksystem.dto;

public record DesktopLoginRequest(
        String username,
        String password,
        String deviceName,
        Boolean force
) {
}
