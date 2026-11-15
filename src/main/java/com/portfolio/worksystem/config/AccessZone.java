package com.portfolio.worksystem.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 접속 존(내부/외부) 판별.
 * - DMZ(web-proxy) Nginx 가 외부 트래픽에 X-Access-Zone=external 을 강제 주입한다.
 * - 단, 사내 PC가 공인 IP로 접속하면 NAT 헤어핀으로 DMZ를 거쳐 들어와 헤더가 붙는다.
 *   이때 실제 접속자 IP(X-Forwarded-For)가 사내 대역이면 '내부'로 본다.
 * → external = (헤더 external) AND (실제 클라이언트 IP가 사설/사내망이 아님)
 */
public final class AccessZone {

    private AccessZone() {
    }

    public static boolean isExternal(HttpServletRequest request) {
        if (request == null || !"external".equalsIgnoreCase(request.getHeader("X-Access-Zone"))) {
            return false;
        }
        return !isPrivate(clientIp(request));
    }

    /** X-Forwarded-For 의 최좌측(원 클라이언트) IP, 없으면 RemoteAddr. */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 사설/사내망(또는 루프백) 여부. RFC1918 + loopback. */
    private static boolean isPrivate(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.")
                || ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31; // 172.16.0.0 ~ 172.31.255.255
                } catch (NumberFormatException ignore) {
                    return false;
                }
            }
        }
        return false;
    }
}
