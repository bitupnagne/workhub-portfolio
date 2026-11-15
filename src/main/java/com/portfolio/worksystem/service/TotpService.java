package com.portfolio.worksystem.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.net.URLEncoder;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * TOTP(RFC 6238) 2차 인증 — 외부 라이브러리 없이 자체 구현(폐쇄망 의존성 회피).
 * - 시크릿: 20바이트 랜덤 → Base32
 * - 코드: HMAC-SHA1, 30초 스텝, 6자리. 검증은 시계 오차 대비 ±1 스텝 허용.
 * - 인증앱(Google/MS Authenticator 등)과 호환되는 표준.
 * (deploy-system TotpService 와 동일 구현)
 */
@Service
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    public int getStepSeconds() {
        return STEP_SECONDS;
    }

    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public String buildOtpAuthUri(String secret, String accountName, String issuer) {
        String label = URLEncoder.encode(issuer + ":" + accountName, StandardCharsets.UTF_8);
        String iss = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secret
            + "&issuer=" + iss + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    public boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (!trimmed.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        long currentStep = System.currentTimeMillis() / 1000L / STEP_SECONDS;
        for (long w = -1; w <= 1; w++) {
            if (generateCode(secret, currentStep + w).equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(String base32Secret, long step) {
        try {
            byte[] key = base32Decode(base32Secret);
            byte[] data = new byte[8];
            long value = step;
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (value & 0xFF);
                value >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            return "";
        }
    }

    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                bitsLeft -= 5;
                sb.append(BASE32.charAt(index));
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            sb.append(BASE32.charAt(index));
        }
        return sb.toString();
    }

    private byte[] base32Decode(String s) {
        String clean = s.trim().replace("=", "").toUpperCase();
        int outLen = clean.length() * 5 / 8;
        byte[] result = new byte[outLen];
        int buffer = 0, bitsLeft = 0, count = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32.indexOf(c);
            if (val < 0) {
                continue;
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[count++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return result;
    }
}
