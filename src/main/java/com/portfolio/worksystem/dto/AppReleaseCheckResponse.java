package com.portfolio.worksystem.dto;

public record AppReleaseCheckResponse(
        boolean updateAvailable,
        String version,
        boolean mandatory,
        String packageType,
        String downloadUrl,
        String sha256,
        Long fileSize,
        String releaseNote
) {
    public static AppReleaseCheckResponse none() {
        return new AppReleaseCheckResponse(false, null, false, null, null, null, null, null);
    }
}
