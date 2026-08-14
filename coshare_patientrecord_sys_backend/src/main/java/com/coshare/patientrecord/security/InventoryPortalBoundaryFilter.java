package com.coshare.patientrecord.security;

import com.coshare.patientrecord.config.PortalMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("mysql")
public class InventoryPortalBoundaryFilter extends OncePerRequestFilter {
    private final PortalMode portalMode;
    private final ObjectMapper objectMapper;
    public InventoryPortalBoundaryFilter(PortalMode portalMode, ObjectMapper objectMapper) { this.portalMode = portalMode; this.objectMapper = objectMapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!portalMode.isInventoryPortal() || allowed(request.getRequestURI())) { chain.doFilter(request, response); return; }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", 403);
        payload.put("msg", "8849 仅提供进销存门户服务");
        payload.put("data", null);
        objectMapper.writeValue(response.getWriter(), payload);
    }

    private boolean allowed(String path) {
        if (path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico") || path.startsWith("/assets/")) return true;
        if (path.equals("/login") || path.equals("/inventory") || path.equals("/inventory/") || path.equals("/inventory/daily")
            || path.startsWith("/inventory-system/")) return true;
        if (path.equals("/health") || path.startsWith("/health/")) return true;
        if (path.equals("/auth/login") || path.equals("/auth/options") || path.equals("/auth/options/accounts")
            || path.equals("/auth/logout") || path.equals("/auth/password") || path.equals("/auth/navigation")) return true;
        return path.startsWith("/inventory-api/");
    }
}
