package com.portfolio.worksystem.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.portfolio.worksystem.mapper.DeploySsoMapper;
import com.portfolio.worksystem.model.DeploySsoLink;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DeploySsoService {

    private final DeploySsoMapper deploySsoMapper;
    private final SystemSettingsService systemSettingsService;
    private final ObjectMapper objectMapper;
    /**
     * ★ 타임아웃이 없으면 배포시스템이 멈췄을 때 <b>오피스가 같이 멈춘다.</b>
     *
     * <p>link/unlink 는 {@code @Transactional} 이라 응답을 기다리는 동안 DB 트랜잭션과
     * 커넥션을 붙들고, issueLoginUrl 은 요청 스레드를 붙든다. 재시도가 몇 번만 겹쳐도
     * 톰캣 스레드 풀이 말라 <b>배포시스템과 무관한 화면까지 전부 느려진다</b> —
     * 두 시스템을 분리해 둔 의미가 사라지는 실패 방식이다.
     *
     * <p>반대 방향(배포시스템 → 오피스 알림)은 처음부터 3초/5초를 걸고 있었다. 같은 값으로 맞춘다.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    @Value("${app.deploy-sso.base-url:${DEPLOY_SYSTEM_URL:}}")
    private String deployBaseUrl;

    @Value("${app.deploy-sso.shared-secret:${DEPLOY_SSO_SHARED_SECRET:}}")
    private String sharedSecret;

    @Value("${app.portfolio.public-demo:${PORTFOLIO_PUBLIC_DEMO:false}}")
    private boolean publicDemo;

    public DeploySsoLink findActive(Long officeUserId) {
        return deploySsoMapper.findActiveByOfficeUserId(officeUserId);
    }

    @Transactional
    public void link(CustomUserDetails user, String code) {
        requireIntegrationEnabled();
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("연동 코드를 입력하세요.");
        }
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                "code", code.trim(),
                "officeUserId", user.getUserId(),
                "officeUsername", user.getUsername()
            ));
            JsonNode root = postJson("/api/internal/sso/link-codes/consume", body);
            if (!root.path("success").asBoolean(false)) {
                throw new BusinessException(root.path("message").asText("SSO 연동에 실패했습니다."));
            }
            JsonNode deployUser = root.path("data");
            deploySsoMapper.upsertLink(
                user.getUserId(),
                user.getUsername(),
                deployUser.path("id").asLong(),
                deployUser.path("loginId").asText(),
                deployUser.path("name").asText()
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("배포시스템 SSO 연동 중 오류가 발생했습니다.");
        }
    }

    /**
     * 연동을 해제한다. <b>양쪽이 같이 끊기거나, 둘 다 안 끊긴다.</b>
     *
     * <p>예전에는 이쪽 {@code deploy_sso_links} 만 비활성화해서 배포시스템의
     * {@code office_sso_link} 는 그대로 남았다. 배포시스템은 그 링크로 <b>알림 수신자를
     * 결정</b>하므로, 연동을 끊은 사용자에게 배포 알림이 계속 갔다.
     *
     * <p>배포시스템에 전달하지 못하면 이쪽 해제도 롤백한다(메서드가 {@code @Transactional}).
     * 화면에는 "끊겼다"고 보이는데 실제로는 계속 알림이 오는 상태가 가장 나쁘다 —
     * 차라리 실패로 알리고 다시 시도하게 한다. 해제 API 는 멱등이라 재시도가 안전하다.
     */
    @Transactional
    public void unlink(CustomUserDetails user) {
        requireIntegrationEnabled();
        deploySsoMapper.deactivate(user.getUserId());
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                "officeUserId", user.getUserId()
            ));
            JsonNode root = postJson("/api/internal/sso/links/revoke", body);
            if (!root.path("success").asBoolean(false)) {
                throw new BusinessException(root.path("message").asText("배포시스템에 연동 해제를 전달하지 못했습니다."));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(
                "배포시스템에 연동 해제를 전달하지 못해 해제를 취소했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    public String issueLoginUrl(CustomUserDetails user) {
        return issueLoginUrl(user, "/deploy/dashboard");
    }

    public String issueLoginUrl(CustomUserDetails user, String redirectPath) {
        requireIntegrationEnabled();
        DeploySsoLink link = findActive(user.getUserId());
        if (link == null) {
            throw new BusinessException("배포시스템 SSO 연동 정보가 없습니다.");
        }
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                "officeUserId", user.getUserId(),
                "officeUsername", user.getUsername(),
                "redirect", normalizeDeployRedirect(redirectPath)
            ));
            JsonNode root = postJson("/api/internal/sso/tickets", body);
            if (!root.path("success").asBoolean(false)) {
                throw new BusinessException(root.path("message").asText("SSO 티켓 발급에 실패했습니다."));
            }
            return root.path("data").path("loginUrl").asText();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("배포시스템 이동 준비 중 오류가 발생했습니다.");
        }
    }

    private String normalizeDeployRedirect(String redirectPath) {
        if (!StringUtils.hasText(redirectPath) || !redirectPath.startsWith("/") || redirectPath.startsWith("//")) {
            return "/deploy/dashboard";
        }
        return redirectPath.length() > 255 ? "/deploy/dashboard" : redirectPath;
    }

    private JsonNode postJson(String path, String body) throws Exception {
        requireIntegrationEnabled();
        String resolvedBaseUrl = settingOrDefault("deploy.base-url", deployBaseUrl);
        String resolvedSecret = settingOrDefault("deploy.sso.shared-secret", sharedSecret);
        if (!StringUtils.hasText(resolvedBaseUrl) || !StringUtils.hasText(resolvedSecret)) {
            throw new BusinessException("배포시스템 연동 주소와 비밀값을 먼저 설정해 주세요.");
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(resolvedBaseUrl.replaceAll("/+$", "") + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-SSO-SECRET", resolvedSecret)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private String settingOrDefault(String key, String defaultValue) {
        String value = systemSettingsService.setting(key, defaultValue);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private void requireIntegrationEnabled() {
        if (publicDemo) throw new BusinessException(com.portfolio.worksystem.config.PortfolioPublicDemoPolicy.MESSAGE);
    }
}
