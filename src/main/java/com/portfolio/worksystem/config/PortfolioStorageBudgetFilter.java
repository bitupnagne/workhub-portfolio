package com.portfolio.worksystem.config;

import com.portfolio.worksystem.service.PortfolioStorageBudget;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Security의 CSRF 파라미터 조회와 MVC multipart 파싱보다 먼저 저장 공간을 예약한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class PortfolioStorageBudgetFilter extends OncePerRequestFilter {
    private final PortfolioStorageBudget storage;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return !storage.isEnabled() || contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PortfolioStorageBudget.Reservation reservation;
        try {
            reservation = storage.reserve(request.getContentLengthLong());
        } catch (PortfolioStorageBudget.LimitExceededException e) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, e.getMessage());
            return;
        } catch (IOException e) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "저장 공간을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }
        try (reservation) {
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
