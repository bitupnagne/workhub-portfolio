package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.security.CustomUserDetailsService;
import com.portfolio.worksystem.service.QrCodeService;
import com.portfolio.worksystem.service.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 로그인 2차 인증(pre-auth). 비밀번호만 통과한 미인증 세션이 OTP를 통과해야 비로소 로그인 완료(재인증)된다.
 *
 * <p>핵심: 이 단계의 세션은 Spring 인증이 없으므로 보호 페이지/ API 는 표준 인가가 전부 차단한다.
 * 따라서 화면 요소를 F12 로 지우거나 콘솔에서 fetch 해도 데이터가 서빙되지 않는다.
 * 모든 작업은 세션에 보관된 pending uid 의 본인 계정에만 적용된다(IDOR 불가).
 */
@Controller
@RequiredArgsConstructor
public class OtpLoginController {
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;

    private static final String ISSUER = "WorkHub";

    private final TotpService totpService;
    private final QrCodeService qrCodeService;
    private final UserMapper userMapper;
    private final CustomUserDetailsService userDetailsService;

    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    /** 로그인 화면 OTP 모달 초기 데이터. 대기 아니면 {pending:false}. 미등록이면 QR/시크릿 동반. */
    @GetMapping("/login/otp/state")
    @ResponseBody
    public Map<String, Object> state(HttpSession session) {
        Map<String, Object> m = new HashMap<>();
        Long uid = (Long) session.getAttribute(OtpController.OTP_PENDING_UID);
        if (uid == null) {
            m.put("pending", false);
            return m;
        }
        boolean enrolled = Boolean.TRUE.equals(session.getAttribute(OtpController.OTP_PENDING_ENROLLED));
        m.put("pending", true);
        m.put("enrolled", enrolled);
        if (!enrolled) {
            String name = (String) session.getAttribute(OtpController.OTP_PENDING_NAME);
            String secret = (String) session.getAttribute(OtpController.OTP_SETUP_SECRET);
            if (secret == null) {
                secret = totpService.generateSecret();
                session.setAttribute(OtpController.OTP_SETUP_SECRET, secret);
            }
            String otpauth = totpService.buildOtpAuthUri(secret, name, ISSUER);
            m.put("account", name);
            m.put("secret", secret);
            m.put("otpauth", otpauth);
            m.put("qrDataUri", qrCodeService.pngDataUri(otpauth, 240));
        }
        return m;
    }

    /** 서버 현재시각 — 모달의 기기·서버 시차 표시는 생략하지만, 진단용으로 유지(미인증 허용). */
    @GetMapping("/login/otp/server-time")
    @ResponseBody
    public Map<String, Object> serverTime() {
        long now = System.currentTimeMillis();
        int step = totpService.getStepSeconds();
        Map<String, Object> m = new HashMap<>();
        m.put("epochMs", now);
        m.put("step", step);
        m.put("remaining", step - ((now / 1000L) % step));
        return m;
    }

    /** OTP 코드 검증. 통과 시 실제 인증(재인증)하고 {ok:true, redirect}. */
    @PostMapping("/login/otp/verify")
    @ResponseBody
    public Map<String, Object> verify(@RequestParam String code, HttpSession session,
                                      HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> m = new HashMap<>();
        Long uid = (Long) session.getAttribute(OtpController.OTP_PENDING_UID);
        String name = (String) session.getAttribute(OtpController.OTP_PENDING_NAME);
        if (uid == null || name == null) {
            m.put("ok", false);
            m.put("expired", true);
            m.put("message", "세션이 만료되었습니다. 다시 로그인하세요.");
            return m;
        }
        boolean enrolled = Boolean.TRUE.equals(session.getAttribute(OtpController.OTP_PENDING_ENROLLED));
        String trimmed = code == null ? "" : code.trim();
        boolean ok;
        if (enrolled) {
            CustomUserDetails u = (CustomUserDetails) userDetailsService.loadUserByUsername(name);
            ok = totpService.verify(u.getOtpSecret(), trimmed);
        } else {
            String secret = (String) session.getAttribute(OtpController.OTP_SETUP_SECRET);
            ok = secret != null && totpService.verify(secret, trimmed);
            if (ok) {
                userMapper.updateOtp(uid, secret, true);
                session.removeAttribute(OtpController.OTP_SETUP_SECRET);
            }
        }
        if (!ok) {
            m.put("ok", false);
            m.put("message", "코드가 올바르지 않습니다. 휴대폰 시간(자동)과 6자리를 확인하세요.");
            return m;
        }
        // OTP 통과 → 실제 로그인 완료(인증 컨텍스트 수립·세션 저장).
        CustomUserDetails principal = (CustomUserDetails) userDetailsService.loadUserByUsername(name);
        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
        session.setAttribute(OtpController.OTP_VERIFIED, Boolean.TRUE);
        session.removeAttribute(OtpController.OTP_PENDING_UID);
        session.removeAttribute(OtpController.OTP_PENDING_NAME);
        session.removeAttribute(OtpController.OTP_PENDING_ENROLLED);
        m.put("ok", true);
        m.put("redirect", releaseFeatures.getHomeUrl());
        return m;
    }
}
