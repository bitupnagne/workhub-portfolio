package com.portfolio.worksystem.dto;

public record DesktopAuthResponse(
        boolean authenticated,
        String token,
        DesktopUserPayload user
) {
}
