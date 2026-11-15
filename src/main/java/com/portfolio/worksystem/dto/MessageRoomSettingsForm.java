package com.portfolio.worksystem.dto;

public record MessageRoomSettingsForm(
        String customTitle,
        Boolean pinned,
        Boolean muted,
        Integer opacityPercent
) {
}
