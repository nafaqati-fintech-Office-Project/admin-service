package com.fintech.admin_service.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every /api/admin/** call needs the ADMIN role. The gateway validates the JWT, removes any client-supplied identity headers
 * and sets X-User-Id / X-User-Roles; this filter repeats the role check so the service stays safe even if a route is
 * misconfigured. The service binds to loopback and is reachable through the gateway only.
 */
@Component
public class AdminAccessFilter extends OncePerRequestFilter {

    static final String ACTOR_ATTRIBUTE = "admin.actor";

    private final JsonMapper jsonMapper;

    public AdminAccessFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID actor = parse(request.getHeader("X-User-Id"));
        if (actor == null) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "Authentication is required");
            return;
        }
        if (!isAdmin(request.getHeader("X-User-Roles"))) {
            reject(request, response, HttpStatus.FORBIDDEN, "Insufficient permissions");
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE, actor.toString());
        chain.doFilter(request, response);
    }

    static boolean isAdmin(String roles) {
        return roles != null && Arrays.stream(roles.split(",")).map(String::trim)
                .anyMatch(r -> r.equalsIgnoreCase("ADMIN") || r.equalsIgnoreCase("ROLE_ADMIN"));
    }

    private static UUID parse(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), Map.of("timestamp", Instant.now().toString(),
                "status", status.value(), "error", status.getReasonPhrase(), "message", message, "path", request.getRequestURI()));
    }
}
