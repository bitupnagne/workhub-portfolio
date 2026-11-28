package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.QrCodeService;
import com.portfolio.worksystem.service.TotpService;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** 오피스 웹 관리자 2차 인증(TOTP). 모달 등록 + 로그인 후 검증 오버레이 + 실시간 서버시계. */
@Controller
@RequiredArgsConstructor
public class OtpController {
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;

    public static final String OTP_VERIFIED = "OTP_VERIFIED";
    public static final String OTP_SETUP_SECRET = "OTP_SETUP_SECRET";
    // 로그인 2차 인증(pre-auth) 단계용 — 비밀번호만 통과한 미인증 세션이 보관하는 대기 정보.
    public static final String OTP_PENDING_UID = "OTP_PENDING_UID";
    public static final String OTP_PENDING_NAME = "OTP_PENDING_NAME";
    public static final String OTP_PENDING_ENROLLED = "OTP_PENDING_ENROLLED";
    private static final String SETUP_SECRET = OTP_SETUP_SECRET;
    private static final String ISSUER = "WorkHub";

    private final TotpService totpService;
    private final QrCodeService qrCodeService;
    private final UserMapper userMapper;

    /** 외부(DMZ) 접속인데 OTP 미등록 → 강제 등록 페이지(standalone, QR+서버시계). */
    @GetMapping("/otp/enroll")
    public String enroll(@AuthenticationPrincipal CustomUserDetails user, HttpSession session, Model model) {
        if (user == null) {
            return "redirect:/login";
        }
        if (user.isOtpEnabled()) {
            return "redirect:/otp";
        }
        String secret = (String) session.getAttribute(SETUP_SECRET);
        if (secret == null) {
            secret = totpService.generateSecret();
            session.setAttribute(SETUP_SECRET, secret);
        }
        String otpauth = totpService.buildOtpAuthUri(secret, user.getUsername(), ISSUER);
        model.addAttribute("account", user.getUsername());
        model.addAttribute("secret", secret);
        model.addAttribute("otpauth", otpauth);
        model.addAttribute("qrDataUri", qrCodeService.pngDataUri(otpauth, 240));
        return "otp-enroll";
    }

    /** 로그인 후 OTP 코드 입력 페이지(모달 스타일 오버레이). */
    @GetMapping("/otp")
    public String otpPage(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null) {
            return "redirect:/login";
        }
        if (!user.isOtpEnabled()) {
            return "redirect:" + releaseFeatures.getHomeUrl();
        }
        return "otp";
    }

    @GetMapping("/otp/server-time")
    @ResponseBody
    public Map<String, Object> serverTime() {
        long now = System.currentTimeMillis();
        int step = totpService.getStepSeconds();
        long remaining = step - ((now / 1000L) % step);
        Map<String, Object> m = new HashMap<>();
        m.put("epochMs", now);
        m.put("step", step);
        m.put("remaining", remaining);
        return m;
    }

    /** 등록 모달용 데이터(시크릿/otpauth/QR). 이미 등록 시 already=true. */
    @GetMapping("/otp/setup/data")
    @ResponseBody
    public Map<String, Object> setupData(@AuthenticationPrincipal CustomUserDetails user, HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        if (user == null) {
            m.put("error", "unauthorized");
            return m;
        }
        if (user.isOtpEnabled()) {
            m.put("already", true);
            return m;
        }
        String secret = (String) session.getAttribute(SETUP_SECRET);
        if (secret == null) {
            secret = totpService.generateSecret();
            session.setAttribute(SETUP_SECRET, secret);
        }
        String otpauth = totpService.buildOtpAuthUri(secret, user.getUsername(), ISSUER);
        m.put("already", false);
        m.put("account", user.getUsername());
        m.put("secret", secret);
        m.put("otpauth", otpauth);
        m.put("qrDataUri", qrCodeService.pngDataUri(otpauth, 240));
        return m;
    }

    /** 등록 확정(모달). */
    @PostMapping("/otp/confirm")
    @ResponseBody
    public Map<String, Object> confirm(@AuthenticationPrincipal CustomUserDetails user,
                                       @RequestParam String code, HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        if (user == null) {
            m.put("ok", false);
            m.put("expired", true);
            m.put("message", "세션이 만료되었습니다. 다시 로그인하세요.");
            return m;
        }
        String secret = (String) session.getAttribute(SETUP_SECRET);
        if (secret != null && totpService.verify(secret, code)) {
            userMapper.updateOtp(user.getUserId(), secret, true);
            user.applyOtpState(secret, true);
            session.removeAttribute(SETUP_SECRET);
            session.setAttribute(OTP_VERIFIED, Boolean.TRUE);
            m.put("ok", true);
            m.put("message", "2차 인증이 등록되었습니다.");
        } else {
            m.put("ok", false);
            m.put("message", "코드가 올바르지 않습니다. 휴대폰 시간(자동)과 6자리를 확인하세요.");
        }
        return m;
    }

    /** 로그인 후 검증(오버레이). */
    @PostMapping("/otp/verify-ajax")
    @ResponseBody
    public Map<String, Object> verifyAjax(@AuthenticationPrincipal CustomUserDetails user,
                                          @RequestParam String code, HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        if (user == null) {
            m.put("ok", false);
            m.put("expired", true);
            m.put("message", "세션이 만료되었습니다.");
            return m;
        }
        if (totpService.verify(user.getOtpSecret(), code)) {
            session.setAttribute(OTP_VERIFIED, Boolean.TRUE);
            m.put("ok", true);
        } else {
            m.put("ok", false);
            m.put("message", "코드가 올바르지 않습니다. 휴대폰 시간(자동)과 6자리를 확인하세요.");
        }
        return m;
    }

    /** 본인 2차 인증 해제(모달). */
    @PostMapping("/otp/off")
    @ResponseBody
    public Map<String, Object> off(@AuthenticationPrincipal CustomUserDetails user, HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        if (user == null) {
            m.put("ok", false);
            m.put("expired", true);
            return m;
        }
        userMapper.updateOtp(user.getUserId(), null, false);
        user.applyOtpState(null, false);
        session.removeAttribute(OTP_VERIFIED);
        m.put("ok", true);
        m.put("message", "2차 인증이 해제되었습니다.");
        return m;
    }
}
