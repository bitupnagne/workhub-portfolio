package com.portfolio.worksystem.model;

import java.util.Map;

public record SystemTheme(
        String brand,
        String brandDark,
        String brandDeeper,
        String brandLight,
        String brandLight2,
        String brandMuted
) {
    public static SystemTheme defaults() {
        return new SystemTheme("#4F46E5", "#4338CA", "#3730A3", "#EEF2FF", "#E0E7FF", "#A5B4FC");
    }

    public static SystemTheme from(Map<String, String> values) {
        SystemTheme d = defaults();
        return new SystemTheme(
                values.getOrDefault("theme.brand", d.brand()),
                values.getOrDefault("theme.brandDark", d.brandDark()),
                values.getOrDefault("theme.brandDeeper", d.brandDeeper()),
                values.getOrDefault("theme.brandLight", d.brandLight()),
                values.getOrDefault("theme.brandLight2", d.brandLight2()),
                values.getOrDefault("theme.brandMuted", d.brandMuted())
        );
    }
}
