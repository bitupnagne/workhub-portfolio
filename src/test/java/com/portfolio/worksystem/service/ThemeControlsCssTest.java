package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.SystemSettingsMapper;
import com.portfolio.worksystem.model.SystemSetting;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThemeControlsCssTest {
    @Test
    void customBrandDrivesControlsAndDarkTintsWithoutFixedColors() {
        var mapper = mock(SystemSettingsMapper.class);
        var brand = new SystemSetting();
        brand.setSettingKey("theme.brand");
        brand.setSettingValue("#7F00FF");
        when(mapper.findAll()).thenReturn(List.of(brand));
        var service = new SystemSettingsService(mapper);

        for (String mode : List.of("light", "dark", "system")) {
            String css = service.themeCss(mode);
            assertThat(css).contains("--system-brand:#7F00FF;",
                    "--system-control-border:", "--system-control-hover-border:",
                    "--system-control-focus:", "--system-control-readonly-border:",
                    "--system-control-readonly-bg:");
            assertThat(css).contains(
                    "--system-control-readonly-bg:color-mix(in srgb,var(--system-brand) 5%,var(--system-surface-2,#f1f5f9));",
                    "--system-control-readonly-border:color-mix(in srgb,var(--system-brand) 20%,var(--system-border-2,#d1d5db));");
            assertThat(css).doesNotContain("%%", "--system-brand:#4F46E5;");
            if (!"light".equals(mode)) {
                assertThat(css).contains("--system-brand-light:color-mix(in srgb,var(--system-brand)",
                        "--system-nav-hover-text:color-mix(in srgb,var(--system-brand)");
            }
        }
    }
}
