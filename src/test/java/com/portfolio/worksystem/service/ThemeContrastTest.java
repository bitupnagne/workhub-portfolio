package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.SystemSettingsMapper;
import com.portfolio.worksystem.model.SystemSetting;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThemeContrastTest {
    @Test
    void tintTextHelperMeetsNormalTextContrastForThemeAndBoundaryColors() throws Exception {
        var method = SystemSettingsService.class.getDeclaredMethod("contrastText", String.class);
        method.setAccessible(true);
        for (int value = 0; value <= 255; value++) {
            String background = "#%02X%02X%02X".formatted(value, value, value);
            assertThat(contrast(background, (String) method.invoke(null, background))).isGreaterThanOrEqualTo(4.5);
        }
        for (String background : java.util.List.of("#4F46E5", "#4338CA", "#3730A3", "#EEF2FF",
                "#808080", "#757575", "#FFFFFF", "#000000", "#FF9900", "#7F00FF")) {
            String foreground = (String) method.invoke(null, background);
            assertThat(contrast(background, foreground)).as(background + " / " + foreground).isGreaterThanOrEqualTo(4.5);
        }
    }

    @Test
    void solidAccentTextIsAlwaysWhiteWhileTintTextKeepsAutomaticContrast() {
        for (String brand : List.of("#4F46E5", "#4338CA", "#3730A3", "#FF9900", "#7F00FF", "#FFFFFF", "#000000")) {
            var mapper = mock(SystemSettingsMapper.class);
            var settings = List.of("theme.brand", "theme.brandDark", "theme.brandDeeper").stream().map(key -> {
                var setting = new SystemSetting();
                setting.setSettingKey(key);
                setting.setSettingValue(brand);
                return setting;
            }).toList();
            when(mapper.findAll()).thenReturn(settings);
            var service = new SystemSettingsService(mapper);
            for (String mode : List.of("light", "dark", "system")) {
                String css = service.themeCss(mode);
                assertThat(css).as(brand + " / " + mode).contains(
                        "--system-on-brand:#FFFFFF;",
                        "--system-on-brand-dark:#FFFFFF;",
                        "--system-on-brand-deeper:#FFFFFF;");
                assertThat(css).contains("--system-on-brand-light:#111827;",
                        "--system-on-brand-light2:#111827;");
            }
        }
    }

    private double contrast(String a, String b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    private double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double value = ((rgb >> (16 - i * 8)) & 255) / 255.0;
            linear[i] = value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
        }
        return .2126 * linear[0] + .7152 * linear[1] + .0722 * linear[2];
    }
}
