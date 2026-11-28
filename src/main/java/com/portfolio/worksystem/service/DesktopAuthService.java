package com.portfolio.worksystem.service;

import com.portfolio.worksystem.controller.OtpController;
import com.portfolio.worksystem.dto.DesktopAuthResponse;
import com.portfolio.worksystem.dto.DesktopAttendancePayload;
import com.portfolio.worksystem.dto.DesktopUserPayload;
import com.portfolio.worksystem.mapper.DesktopAuthMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.DesktopDeviceToken;
import com.portfolio.worksystem.model.DesktopSsoToken;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.security.LoginAttemptService;
import com.portfolio.worksystem.websocket.RealtimeHub;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RequiredArgsConstructor
@Service
public class DesktopAuthService {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DesktopAuthMapper desktopAuthMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RealtimeHub realtimeHub;
    private final LoginAttemptService loginAttemptService;
    private final SystemSettingsService systemSettingsService;

    /**
     * [보안] OTP가 필요한데 아직 검증하지 못한 데스크톱 로그인 세션 표시.
     * 이 값이 세션에 있으면 {@code DesktopOtpPendingGuardConfig} 가 OTP 엔드포인트 외 보호 리소스를 401로 차단한다.
     * (웹은 SecurityContext를 비워 차단하지만, 데스크톱은 토큰 발급 후 세션이 서서 별도 게이트가 필요.)
     */
    public static final String OTP_PENDING_SESSION = "DESKTOP_OTP_PENDING";

    @Transactional
    public DesktopAuthResponse login(String username, String password, String deviceName, boolean force, HttpServletRequest request) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new ResponseStatusException(UNAUTHORIZED, "아이디와 비밀번호를 입력하세요.");
        }
        // brute-force 방어(웹 로그인과 동일 정책: 5회 실패 시 10분 잠금). 429로 구분 가능하게.
        if (loginAttemptService.isBlocked(username)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "로그인 실패가 많아 약 10분간 잠겼습니다. 잠시 후 다시 시도하세요.");
        }
        UserAccount user = userMapper.findByUsername(username.trim());
        if (user == null || !Boolean.TRUE.equals(user.getActive()) || !passwordEncoder.matches(password, user.getPassword())) {
            loginAttemptService.recordFailure(username);
            throw new ResponseStatusException(UNAUTHORIZED, "로그인 정보가 올바르지 않습니다.");
        }
        loginAttemptService.reset(username);
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        DesktopDeviceToken activeToken = desktopAuthMapper.findLatestActiveDeviceTokenByUserId(user.getId(), now);
        if (activeToken != null && !force) {
            throw new DesktopLoginConflictException(activeToken.getDeviceName());
        }
        if (activeToken != null) {
            desktopAuthMapper.revokeDeviceTokensByUserId(user.getId(), now);
            realtimeHub.sendToUser(user.getId(), "KICKED", Map.of(
                    "reasonText", "다른 PC에서 로그인되어 세션이 종료되었습니다.",
                    "deviceName", normalizeDeviceName(deviceName)
            ));
        }
        CustomUserDetails principal = principal(user);
        establishSession(principal, request);

        String rawToken = newToken();
        DesktopDeviceToken token = new DesktopDeviceToken();
        token.setUserId(user.getId());
        token.setTokenHash(hash(rawToken));
        token.setDeviceName(normalizeDeviceName(deviceName));
        token.setExpiresAt(now.plusDays(90));
        desktopAuthMapper.insertDeviceToken(token);

        applyDesktopOtpGate(principal, request);
        return new DesktopAuthResponse(true, rawToken, userPayload(principal));
    }

    @Transactional
    public DesktopAuthResponse refresh(String rawToken, HttpServletRequest request) {
        if (!StringUtils.hasText(rawToken)) {
            throw new ResponseStatusException(UNAUTHORIZED, "자동 로그인 토큰이 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        DesktopDeviceToken token = desktopAuthMapper.findActiveDeviceToken(hash(rawToken), now);
        if (token == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "자동 로그인 정보가 만료되었습니다.");
        }
        UserAccount user = userMapper.findById(token.getUserId());
        if (user == null || !Boolean.TRUE.equals(user.getActive())) {
            throw new ResponseStatusException(UNAUTHORIZED, "사용자를 찾을 수 없습니다.");
        }
        desktopAuthMapper.markDeviceTokenUsed(token.getId(), now);
        CustomUserDetails principal = principal(user);
        establishSession(principal, request);
        applyDesktopOtpGate(principal, request);
        return new DesktopAuthResponse(true, null, userPayload(principal));
    }

    @Transactional
    public void revoke(String rawToken) {
        if (StringUtils.hasText(rawToken)) {
            desktopAuthMapper.revokeDeviceToken(hash(rawToken), LocalDateTime.now(SERVICE_ZONE));
        }
    }

    @Transactional
    public String createSsoToken(CustomUserDetails user, String redirectPath, HttpServletRequest request) {
        String rawToken = newToken();
        DesktopSsoToken token = new DesktopSsoToken();
        token.setUserId(user.getUserId());
        token.setTokenHash(hash(rawToken));
        token.setRedirectPath(normalizeRedirect(redirectPath));
        var session = request.getSession(false);
        token.setOtpVerified(session != null
                && Boolean.TRUE.equals(session.getAttribute(OtpController.OTP_VERIFIED))
                && !Boolean.TRUE.equals(session.getAttribute(OTP_PENDING_SESSION)));
        token.setExpiresAt(LocalDateTime.now(SERVICE_ZONE).plusMinutes(2));
        desktopAuthMapper.insertSsoToken(token);
        return rawToken;
    }

    @Transactional
    public String consumeSso(String rawToken, HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        DesktopSsoToken token = desktopAuthMapper.findUsableSsoToken(hash(rawToken), now);
        if (token == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "웹 로그인 토큰이 만료되었습니다.");
        }
        UserAccount user = userMapper.findById(token.getUserId());
        if (user == null || !Boolean.TRUE.equals(user.getActive())) {
            throw new ResponseStatusException(UNAUTHORIZED, "사용자를 찾을 수 없습니다.");
        }
        if (desktopAuthMapper.markSsoTokenUsed(token.getId(), now) != 1) {
            throw new ResponseStatusException(UNAUTHORIZED, "웹 로그인 토큰이 만료되었습니다.");
        }
        establishSession(principal(user), request);
        if (token.isOtpVerified()) {
            request.getSession(true).setAttribute(OtpController.OTP_VERIFIED, Boolean.TRUE);
        }
        return normalizeRedirect(token.getRedirectPath());
    }

    public DesktopUserPayload userPayload(CustomUserDetails user) {
        return new DesktopUserPayload(
                user.getUserId(),
                user.getUsername(),
                user.getName(),
                user.getTeamName(),
                user.getDept(),
                user.getJobTitle(),
                user.getEmail(),
                user.getMobileNumber(),
                user.getExtensionNumber(),
                user.getProfileImageFilename(),
                user.isMustChangePassword()
        );
    }

    public DesktopAttendancePayload attendancePayload(AttendanceRecord record) {
        return attendancePayload(record, null, null);
    }

    public DesktopAttendancePayload attendancePayload(AttendanceRecord record, String scheduleText) {
        return attendancePayload(record, scheduleText, null);
    }

    /** [W7] scheduleText = 오늘 기준 근무시간(요일별 근무시간표 C3·시차출근 반영) — 위젯 표기용.
     *  [W8] blockReason = 오늘 출근 체크 불가 사유(웹 A1과 동일 규칙) — 앱 버튼 사전 비활성용. */
    public DesktopAttendancePayload attendancePayload(AttendanceRecord record, String scheduleText, String blockReason) {
        if (record == null) {
            return new DesktopAttendancePayload(false, false, null, null, null, "근무 대기", scheduleText, blockReason, null, null);
        }
        String checkInAt = record.getCheckInAt() == null ? null : record.getCheckInAt().format(TIME_FORMAT);
        String checkOutAt = record.getCheckOutAt() == null ? null : record.getCheckOutAt().format(TIME_FORMAT);
        String status = record.getStatus() == null ? null : record.getStatus().name();
        String statusLabel = record.getStatus() == null ? "근무 대기" : record.getStatus().getLabel();
        return new DesktopAttendancePayload(checkInAt != null, checkOutAt != null, checkInAt, checkOutAt,
                status, statusLabel, scheduleText, blockReason, null, null);
    }

    /** [OT] 시간외 기록을 위젯의 출퇴근 시각으로 내보낸다 — 근태 판정 없이 '시간외 근무'로만 표시. */
    public DesktopAttendancePayload overtimePayload(com.portfolio.worksystem.model.OvertimeSession session, String scheduleText) {
        String checkInAt = session.getCheckInAt().format(TIME_FORMAT);
        String checkOutAt = session.getCheckOutAt() == null ? null : session.getCheckOutAt().format(TIME_FORMAT);
        return new DesktopAttendancePayload(true, checkOutAt != null, checkInAt, checkOutAt,
                "OVERTIME", "시간외 근무", scheduleText, null, null, null);
    }

    public LocalDateTime ssoExpiresAt() {
        return LocalDateTime.now(SERVICE_ZONE).plusMinutes(2);
    }

    private CustomUserDetails principal(UserAccount user) {
        List<String> roles = userMapper.findRolesByUserId(user.getId());
        user.setRoles(roles);
        return new CustomUserDetails(user);
    }

    private void establishSession(CustomUserDetails principal, HttpServletRequest request) {
        var session = request.getSession(true);
        session.removeAttribute(OtpController.OTP_VERIFIED);
        session.removeAttribute(OtpController.OTP_SETUP_SECRET);
        session.removeAttribute(OTP_PENDING_SESSION);
        session.removeAttribute(OtpController.OTP_PENDING_UID);
        session.removeAttribute(OtpController.OTP_PENDING_NAME);
        session.removeAttribute(OtpController.OTP_PENDING_ENROLLED);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, principal.getPassword(), principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }

    /** 웹 SecurityConfig와 동일한 OTP 필요 판정(관리자 롤 전체 포함). */
    private boolean otpRequired(CustomUserDetails principal) {
        return principal.isOtpEnabled()
                || systemSettingsService.otpRequireUser()
                || (systemSettingsService.otpRequireAdmin() && principal.hasAnyAdminRole());
    }

    /**
     * [보안] 데스크톱 로그인/자동로그인 직후 OTP 게이트 설정.
     * OTP 필요 계정이면 세션을 '검증 대기'로 두어 verify/enroll 전까지 보호 리소스를 차단한다.
     * SSO(웹 열기)는 토큰에 저장된 OTP 검증 증거만 전달한다.
     */
    private void applyDesktopOtpGate(CustomUserDetails principal, HttpServletRequest request) {
        var session = request.getSession(true);
        if (otpRequired(principal)) {
            session.setAttribute(OTP_PENDING_SESSION, Boolean.TRUE);
        } else {
            session.removeAttribute(OTP_PENDING_SESSION);
        }
    }

    private String normalizeDeviceName(String deviceName) {
        if (!StringUtils.hasText(deviceName)) {
            return "Windows";
        }
        return deviceName.trim().length() > 120 ? deviceName.trim().substring(0, 120) : deviceName.trim();
    }

    private String normalizeRedirect(String redirect) {
        if (!StringUtils.hasText(redirect) || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return "/org";
        }
        return redirect.length() > 255 ? "/org" : redirect;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("토큰 해시에 실패했습니다.", e);
        }
    }
}
