package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.DeployNotificationRequest;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.CommunicationService;
import com.portfolio.worksystem.service.SystemSettingsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class InternalDeployNotificationController {

    private final CommunicationService communicationService;
    private final SystemSettingsService systemSettingsService;

    @Value("${app.deploy-sso.shared-secret:${DEPLOY_SSO_SHARED_SECRET:}}")
    private String sharedSecret;

    @PostMapping("/api/internal/deploy/notifications")
    public Map<String, Object> receiveDeployNotification(
            @RequestHeader(value = "X-SSO-SECRET", required = false) String secret,
            @RequestBody DeployNotificationRequest request
    ) {
        requireSecret(secret);
        if (request == null || request.recipientOfficeUserId() == null || !StringUtils.hasText(request.title())) {
            throw new BusinessException("배포시스템 알림 정보가 올바르지 않습니다.");
        }
        communicationService.notifyUser(
                request.recipientOfficeUserId(),
                request.senderOfficeUserId(),
                normalizeType(request.type()),
                request.title().trim(),
                trimToNull(request.body()),
                trimToNull(request.linkUrl())
        );
        return Map.of("success", true);
    }

    private void requireSecret(String secret) {
        String expected = systemSettingsService.setting("deploy.sso.shared-secret", sharedSecret);
        if (!StringUtils.hasText(expected)) {
            expected = sharedSecret;
        }
        // [감사] 타이밍 공격 방지 — 상수시간 비교
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(secret)
                || !java.security.MessageDigest.isEqual(
                        expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new BusinessException("배포시스템 알림 호출 권한이 없습니다.");
        }
    }

    private String normalizeType(String type) {
        String value = trimToNull(type);
        return value == null ? "DEPLOY" : value;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
