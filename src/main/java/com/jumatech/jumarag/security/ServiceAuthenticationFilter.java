package com.jumatech.jumarag.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jumatech.jumarag.shared.web.ApiError;
import com.jumatech.jumarag.shared.web.RequestCorrelationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "app.runtime", havingValue = "api", matchIfMissing = true)
public final class ServiceAuthenticationFilter extends OncePerRequestFilter {
    public static final String CLIENT_API_KEY_HEADER = "X-JumaRAG-API-Key";
    private static final String ADMIN_PREFIX = "/v1/admin/";
    private final byte[] adminToken;
    private final ApiClientService clients;
    private final ObjectMapper objectMapper;

    public ServiceAuthenticationFilter(
            SecurityProperties properties, ApiClientService clients, ObjectMapper objectMapper) {
        this.adminToken = properties.adminToken().getBytes(StandardCharsets.UTF_8);
        this.clients = clients;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/v1/")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (path.startsWith(ADMIN_PREFIX)) {
            if (!validAdminBearer(request.getHeader("Authorization"))) {
                unauthorized(response, "ADMIN_AUTHENTICATION_REQUIRED");
                return;
            }
        } else {
            var authenticated = clients.authenticate(request.getHeader(CLIENT_API_KEY_HEADER));
            if (authenticated.isEmpty()) {
                unauthorized(response, "CLIENT_AUTHENTICATION_REQUIRED");
                return;
            }
            request.setAttribute(ApiClientService.CLIENT_ID_ATTRIBUTE, authenticated.get().clientId());
        }
        filterChain.doFilter(request, response);
    }

    private boolean validAdminBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] presented = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(adminToken, presented);
    }

    private void unauthorized(HttpServletResponse response, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiError(code, "Valid service credentials are required.", MDC.get(RequestCorrelationFilter.REQUEST_ID)));
    }
}
