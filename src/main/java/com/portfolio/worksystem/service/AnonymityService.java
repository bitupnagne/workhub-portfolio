package com.portfolio.worksystem.service;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 진짜 익명 토큰 발급기.
 *
 * 익명 투표/댓글은 DB에 user_id를 저장하지 않고, 오직 이 토큰만 저장한다.
 * token = HMAC-SHA256(서버비밀키, scope) 의 hex(64자).
 * - 서버 비밀키는 DB가 아닌 앱 환경변수(ANONYMITY_SECRET)에만 존재한다.
 * - HMAC 단방향성으로 토큰만으로는 사용자를 역추적할 수 없다(비밀키 없이는 사실상 불가).
 * - 같은 (scope,user)는 항상 같은 토큰 → UNIQUE 제약으로 1인 1표/중복 방지는 되지만,
 *   토큰 자체에는 사용자 식별 정보가 전혀 없어 DB만 보면 누가 했는지 알 수 없다.
 */
@Service
public class AnonymityService {
    private final byte[] secret;

    public AnonymityService(@Value("${app.anonymity.secret:${ANONYMITY_SECRET}}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String voterToken(Long pollId, Long userId) {
        return hmacHex("poll:" + pollId + ":" + userId);
    }

    public String commentToken(Long noticeId, Long userId) {
        return hmacHex("comment:" + noticeId + ":" + userId);
    }

    private String hmacHex(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("익명 토큰 생성 실패", e);
        }
    }
}
