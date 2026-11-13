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
public class LabPortalBoundaryFilter extends OncePerRequestFilter {

    private final PortalMode portalMode;
    private final ObjectMapper objectMapper;

    public LabPortalBoundaryFilter(PortalMode portalMode, ObjectMapper objectMapper) {
        this.portalMode = portalMode;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!portalMode.isLabPortal() || allowed(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", 403);
        payload.put("msg", "该地址仅提供患者检验报告查询服务");
        payload.put("data", null);
        objectMapper.writeValue(response.getWriter(), payload);
    }

    private boolean allowed(String path) {
        if (path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico") || path.equals("/logo.png")) return true;
        if (path.startsWith("/assets/")) return true;
        if (path.equals("/health") || path.startsWith("/health/")) return true;
        return path.startsWith("/lab-api/");
    }
}
