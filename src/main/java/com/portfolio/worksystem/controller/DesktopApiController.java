package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.DesktopAuthResponse;
import com.portfolio.worksystem.dto.DesktopAttendancePayload;
import com.portfolio.worksystem.dto.DesktopLoginRequest;
import com.portfolio.worksystem.dto.DesktopRefreshRequest;
import com.portfolio.worksystem.dto.DesktopSsoRequest;
import com.portfolio.worksystem.dto.DesktopSsoResponse;
import com.portfolio.worksystem.dto.DesktopUserPayload;
import com.portfolio.worksystem.model.ApprovalInboxItem;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.ApprovalService;
import com.portfolio.worksystem.service.DesktopAuthService;
import com.portfolio.worksystem.service.DesktopLoginConflictException;
import com.portfolio.worksystem.service.HrService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@RequiredArgsConstructor
@RestController
public class DesktopApiController {
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final DesktopAuthService desktopAuthService;
    private final HrService hrService;
    private final ApprovalService approvalService;
    private final com.portfolio.worksystem.service.DeploySsoService deploySsoService;
    private final com.portfolio.worksystem.service.TotpService totpService;
    private final com.portfolio.worksystem.service.QrCodeService qrCodeService;
    private final com.portfolio.worksystem.mapper.UserMapper userMapper;
    private final com.portfolio.worksystem.service.SystemSettingsService systemSettingsService;
    private final com.portfolio.worksystem.service.AccountService accountService;

    @PostMapping("/api/desktop/login")
    public DesktopAuthResponse login(@RequestBody DesktopLoginRequest form, HttpServletRequest request) {
        return desktopAuthService.login(form.username(), form.password(), form.deviceName(),
                Boolean.TRUE.equals(form.force()), request);
    }

    @PostMapping("/api/desktop/refresh")
    public DesktopAuthResponse refresh(@RequestBody DesktopRefreshRequest form, HttpServletRequest request) {
        return desktopAuthService.refresh(form.token(), request);
    }

    @PostMapping("/api/desktop/logout")
    public void logout(@RequestBody(required = false) DesktopRefreshRequest form, HttpServletRequest request) {
        if (form != null) {
            desktopAuthService.revoke(form.token());
        }
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @PostMapping("/api/desktop/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @AuthenticationPrincipal CustomUserDetails user,
            @jakarta.validation.Valid @RequestBody com.portfolio.worksystem.dto.PasswordChangeForm form) {
        try {
            accountService.changePassword(user, form);
            user.markPasswordChanged();
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (com.portfolio.worksystem.service.BusinessException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/api/desktop/me")
    public DesktopUserPayload me(@AuthenticationPrincipal CustomUserDetails user) {
        return desktopAuthService.userPayload(user);
    }

    /** 데스크톱 앱이 "웹 열기" 2단 메뉴(오피스/배포)에서 배포 항목 노출 여부를 판단하기 위한 연동 상태. */
    @GetMapping("/api/desktop/integrations")
    public Map<String, Object> integrations(@AuthenticationPrincipal CustomUserDetails user) {
        boolean deployLinked = user != null && deploySsoService.findActive(user.getUserId()) != null;
        return Map.of("deploySsoLinked", deployLinked);
    }

    // ── 데스크톱 앱 OTP (시작 시 모달) ──────────────────────────
    private static final String OTP_ISSUER = "WorkHub";

    private boolean otpRequiredFor(CustomUserDetails user) {
        return user.isOtpEnabled()
                || systemSettingsService.otpRequireUser()
                // 웹 SecurityConfig와 동일하게 관리자 롤 전체에 적용(시스템관리자에 국한하지 않음)
                || (systemSettingsService.otpRequireAdmin() && user.hasAnyAdminRole());
    }

    /** 앱 시작 시: 이 계정에 OTP가 필요한지/등록됐는지. */
    @GetMapping("/api/desktop/otp/state")
    public Map<String, Object> otpState(@AuthenticationPrincipal CustomUserDetails user) {
        return Map.of("required", otpRequiredFor(user), "enrolled", user.isOtpEnabled());
    }

    /** 등록용 QR/키(미등록 계정). */
    @GetMapping("/api/desktop/otp/setup")
    public Map<String, Object> otpSetup(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request) {
        if (user.isOtpEnabled()) {
            return Map.of("already", true);
        }
        String secret = totpService.generateSecret();
        request.getSession(true).setAttribute("OTP_SETUP_SECRET", secret);
        String otpauth = totpService.buildOtpAuthUri(secret, user.getUsername(), OTP_ISSUER);
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("already", false);
        m.put("secret", secret);
        m.put("otpauth", otpauth);
        m.put("qrDataUri", qrCodeService.pngDataUri(otpauth, 240));
        return m;
    }

    /** 등록 확정. */
    @PostMapping("/api/desktop/otp/enroll")
    public Map<String, Object> otpEnroll(@AuthenticationPrincipal CustomUserDetails user,
                                         @RequestBody Map<String, String> body, HttpServletRequest request) {
        String secret = (String) request.getSession(true).getAttribute("OTP_SETUP_SECRET");
        String code = body == null ? null : body.get("code");
        if (secret != null && totpService.verify(secret, code)) {
            userMapper.updateOtp(user.getUserId(), secret, true);
            user.applyOtpState(secret, true);
            request.getSession(true).removeAttribute("OTP_SETUP_SECRET");
            request.getSession(true).setAttribute(OtpController.OTP_VERIFIED, Boolean.TRUE);
            // [보안] OTP 등록 완료 → 데스크톱 OTP 게이트 해제
            request.getSession(true).removeAttribute(com.portfolio.worksystem.service.DesktopAuthService.OTP_PENDING_SESSION);
            return Map.of("ok", true);
        }
        return Map.of("ok", false, "message", "코드가 올바르지 않습니다.");
    }

    /** 검증(등록된 계정). */
    @PostMapping("/api/desktop/otp/verify")
    public Map<String, Object> otpVerify(@AuthenticationPrincipal CustomUserDetails user,
                                         @RequestBody Map<String, String> body,
                                         HttpServletRequest request) {
        String code = body == null ? null : body.get("code");
        boolean ok = user.isOtpEnabled() && totpService.verify(user.getOtpSecret(), code);
        if (ok) {
            request.getSession(true).setAttribute(OtpController.OTP_VERIFIED, Boolean.TRUE);
            // [보안] OTP 검증 성공 → 데스크톱 OTP 게이트 해제
            request.getSession(true).removeAttribute(com.portfolio.worksystem.service.DesktopAuthService.OTP_PENDING_SESSION);
        }
        return ok ? Map.of("ok", true) : Map.of("ok", false, "message", "코드가 올바르지 않습니다.");
    }

    @GetMapping("/api/desktop/attendance/today")
    public DesktopAttendancePayload todayAttendance(@AuthenticationPrincipal CustomUserDetails user) {
        // [W8] 웹(A1)과 동일 — 휴무일·휴직·승인 출장/휴가면 사유를 내려 앱이 버튼을 사전 비활성한다
        return desktopAuthService.attendancePayload(hrService.todayAttendance(user), todayScheduleText(user),
                hrService.todayCheckBlockReason(user.getUserId()));
    }

    /** [W7] 오늘 기준 근무시간 표기 — C3(요일별 근무시간표)·시차출근·일자예외 반영. */
    private String todayScheduleText(CustomUserDetails user) {
        try {
            var schedule = hrService.workSchedule(user.getUserId(), LocalDate.now());
            String text = schedule.startTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    + "~" + schedule.endTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            if (!"기본 근무시간".equals(schedule.note())) {
                text += " (" + schedule.note() + ")";
            }
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/api/desktop/schedules")
    public List<DesktopCompanySchedulePayload> schedules(@RequestParam LocalDate from,
                                                         @RequestParam LocalDate to) {
        List<DesktopCompanySchedulePayload> schedules = hrService.companySchedules(from, to).stream()
                .map(schedule -> new DesktopCompanySchedulePayload(
                        schedule.getId(),
                        schedule.getTitle(),
                        schedule.getDescription(),
                        schedule.getStartAt() == null ? null : schedule.getStartAt().format(ISO_FORMAT),
                        schedule.getEndAt() == null ? null : schedule.getEndAt().format(ISO_FORMAT),
                        schedule.getColor(),
                        schedule.getCreatedByName(),
                        false
                ))
                .toList();
        List<DesktopCompanySchedulePayload> holidays = hrService.companyHolidays(from, to).stream()
                .map(holiday -> new DesktopCompanySchedulePayload(
                        holiday.getId(),
                        StringUtils.hasText(holiday.getName()) ? holiday.getName() : "휴무",
                        "회사 휴무일",
                        holiday.getHolidayDate() == null ? null : holiday.getHolidayDate().atStartOfDay().format(ISO_FORMAT),
                        holiday.getHolidayDate() == null ? null : holiday.getHolidayDate().atTime(23, 59, 59).format(ISO_FORMAT),
                        "#EF4444",
                        holiday.getCreatedByName(),
                        true
                ))
                .toList();
        return java.util.stream.Stream.concat(schedules.stream(), holidays.stream())
                .sorted(java.util.Comparator.comparing(DesktopCompanySchedulePayload::startAt,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @GetMapping("/api/desktop/documents")
    public List<DesktopDocumentPayload> documents(@AuthenticationPrincipal CustomUserDetails user,
                                                  @RequestParam(defaultValue = "all") String box,
                                                  @RequestParam(defaultValue = "50") int limit) {
        String normalizedBox = switch (box == null ? "" : box.trim()) {
            case "pending" -> "pending";
            case "completed" -> "completed";
            case "requested", "request" -> "requested";
            case "after" -> "after";
            default -> "all";
        };
        List<DesktopDocumentWithBox> items;
        if ("all".equals(normalizedBox)) {
            var map = new java.util.LinkedHashMap<String, DesktopDocumentWithBox>();
            for (var it : approvalService.inbox(user)) map.put(it.getDocumentType() + ":" + it.getDocumentId(), new DesktopDocumentWithBox(it, "pending"));
            for (String b : new String[]{"requested", "completed", "after"}) {
                for (var it : approvalService.documents(user, b)) map.putIfAbsent(it.getDocumentType() + ":" + it.getDocumentId(), new DesktopDocumentWithBox(it, b));
            }
            items = new java.util.ArrayList<>(map.values());
        } else if ("pending".equals(normalizedBox)) {
            items = approvalService.inbox(user).stream()
                    .map(item -> new DesktopDocumentWithBox(item, "pending"))
                    .toList();
        } else {
            items = approvalService.documents(user, normalizedBox).stream()
                    .map(item -> new DesktopDocumentWithBox(item, normalizedBox))
                    .toList();
        }
        return items.stream()
                .sorted(java.util.Comparator.comparing(item -> item.item().getCreatedAt(), java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(Math.max(1, Math.min(limit, 50)))
                .map(row -> {
                    ApprovalInboxItem item = row.item();
                    return new DesktopDocumentPayload(
                        item.getDocumentType() == null ? null : item.getDocumentType().name(),
                        item.getDocumentType() == null ? "-" : item.getDocumentType().getLabel(),
                        item.getDocumentId(),
                        item.getRequesterName(),
                        item.getTitle(),
                        item.getCreatedAt() == null ? null : item.getCreatedAt().format(ISO_FORMAT),
                        documentUrl(item),
                        row.box()
                    );
                })
                .toList();
    }

    @PostMapping("/api/desktop/attendance/check-in")
    public DesktopAttendancePayload checkIn(@AuthenticationPrincipal CustomUserDetails user) {
        hrService.checkIn(user);
        return desktopAuthService.attendancePayload(hrService.todayAttendance(user));
    }

    @PostMapping("/api/desktop/attendance/check-out")
    public DesktopAttendancePayload checkOut(@AuthenticationPrincipal CustomUserDetails user) {
        hrService.checkOut(user);
        return desktopAuthService.attendancePayload(hrService.todayAttendance(user));
    }

    @PostMapping("/api/desktop/sso-token")
    public DesktopSsoResponse ssoToken(@AuthenticationPrincipal CustomUserDetails user,
                                       @RequestBody(required = false) DesktopSsoRequest form,
                                       HttpServletRequest request) {
        String redirect = form == null || !StringUtils.hasText(form.redirect()) ? "/org" : form.redirect();
        String token = desktopAuthService.createSsoToken(user, redirect, request);
        return new DesktopSsoResponse("/desktop/sso/" + token, desktopAuthService.ssoExpiresAt().format(ISO_FORMAT));
    }

    @GetMapping("/desktop/sso/{token}")
    public RedirectView sso(@PathVariable String token, HttpServletRequest request) {
        String redirect = desktopAuthService.consumeSso(token, request);
        return new RedirectView(redirect);
    }

    @ExceptionHandler(DesktopLoginConflictException.class)
    public ResponseEntity<Map<String, Object>> desktopLoginConflict(DesktopLoginConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "conflict", true,
                "existingDevice", e.getExistingDevice()
        ));
    }

    /** [W3] 데스크톱 API의 BusinessException은 JSON으로 — 전역 핸들러(HTML 뷰) 경유 시
     *  앱이 실패 사유를 알 수 없던 문제 수정. 앱은 message를 모달에 그대로 표시한다. */
    @ExceptionHandler(com.portfolio.worksystem.service.BusinessException.class)
    public ResponseEntity<Map<String, Object>> desktopBusiness(com.portfolio.worksystem.service.BusinessException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("message", e.getMessage()));
    }

    public record DesktopCompanySchedulePayload(Long id,
                                                String title,
                                                String description,
                                                String startAt,
                                                String endAt,
                                                String color,
                                                String createdByName,
                                                boolean holiday) {
    }

    public record DesktopDocumentPayload(String documentType,
                                         String documentTypeLabel,
                                         Long documentId,
                                         String requesterName,
                                         String title,
                                         String createdAt,
                                         String documentUrl,
                                         String box) {
    }

    private record DesktopDocumentWithBox(ApprovalInboxItem item, String box) {
    }

    private String documentUrl(ApprovalInboxItem item) {
        if (item.getDocumentType() == null || item.getDocumentId() == null) return "/hr/documents";
        return switch (item.getDocumentType()) {
            case LEAVE, LEAVE_CANCEL -> "/hr/leave/" + item.getDocumentId() + "/document";
            case TRIP -> "/hr/trips/" + item.getDocumentId() + "/document";
            case TRIP_REPORT -> "/hr/trips/report/" + item.getDocumentId() + "/document";
            case FLEX_TIME -> "/hr/flex-time/" + item.getDocumentId() + "/document";
            case ATTENDANCE_CORRECTION -> "/hr/attendance/correction/" + item.getDocumentId() + "/document";
            case LOA -> "/hr/loa/" + item.getDocumentId() + "/document";
        };
    }
}
