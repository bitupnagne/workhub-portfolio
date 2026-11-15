package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.model.SystemTheme;
import com.portfolio.worksystem.model.SystemBranding;
import com.portfolio.worksystem.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import java.net.URI;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class SystemSettingsController {
    private final SystemSettingsService systemSettingsService;

    @GetMapping("/api/system/theme")
    public SystemTheme theme() {
        return systemSettingsService.theme();
    }

    @GetMapping("/api/system/branding")
    public SystemBranding branding() {
        return systemSettingsService.branding();
    }

    @GetMapping(value = "/system-theme.css", produces = "text/css; charset=UTF-8")
    public ResponseEntity<String> themeCss(
            @org.springframework.web.bind.annotation.CookieValue(name = "wsTheme", required = false) String wsTheme) {
        // 사용자별 다크모드: 브라우저 쿠키(wsTheme)로 개인 선택을 우선 적용.
        // 쿠키마다 응답이 달라지므로 private + no-cache 로 사용자 간 캐시 혼선 방지.
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-cache")
                .header("Vary", "Cookie")
                .contentType(new MediaType("text", "css", java.nio.charset.StandardCharsets.UTF_8))
                .body(systemSettingsService.themeCss(wsTheme));
    }

    @GetMapping("/system-logos/{filename:.+}")
    public ResponseEntity<Resource> logo(@PathVariable String filename) {
        SystemSettingsService.StoredLogo logo = systemSettingsService.loadLogo(filename);
        return ResponseEntity.ok()
                .contentType(logo.mediaType())
                .body(logo.resource());
    }

    @GetMapping("/system-logo/web")
    public ResponseEntity<Void> webLogo() {
        return ResponseEntity.status(302)
                .location(URI.create(systemSettingsService.branding().webLogoUrl()))
                .build();
    }

    @GetMapping("/system-logo/desktop")
    public ResponseEntity<Void> desktopLogo() {
        return ResponseEntity.status(302)
                .location(URI.create(systemSettingsService.branding().desktopLogoUrl()))
                .build();
    }
}
