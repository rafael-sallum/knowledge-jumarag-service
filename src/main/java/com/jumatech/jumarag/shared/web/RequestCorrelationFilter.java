package com.jumatech.jumarag.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = safeRequestId(request.getHeader("X-Request-Id"));
        try (MDC.MDCCloseable ignored = MDC.putCloseable(REQUEST_ID, requestId)) {
            response.setHeader("X-Request-Id", requestId);
            filterChain.doFilter(request, response);
        }
    }

    private String safeRequestId(String candidate) {
        if (candidate != null && candidate.length() <= 80 && candidate.matches("[A-Za-z0-9._:-]+")) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
