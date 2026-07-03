package com.coshare.patientrecord.common.observability;

import com.coshare.patientrecord.auth.dto.SessionUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    private static final int MAX_REQUEST_ID_LENGTH = 80;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/assets/")
            || path.equals("/favicon.ico")
            || path.equals("/index.html");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        long startedAt = System.currentTimeMillis();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(TRACE_HEADER, requestId);

        int status = 500;
        try {
            filterChain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.info(
                "HTTP request completed, requestId={}, method={}, path={}, status={}, durationMs={}, user={}, remote={}",
                requestId,
                request.getMethod(),
                request.getRequestURI(),
                status,
                durationMs,
                currentUserLabel(),
                remoteAddress(request)
            );
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(TRACE_HEADER);
        if (header == null || header.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String value = header.trim();
        if (value.length() > MAX_REQUEST_ID_LENGTH || !value.matches("[A-Za-z0-9._-]+")) {
            return UUID.randomUUID().toString();
        }
        return value;
    }

    private String currentUserLabel() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof SessionUser user) {
            return user.name() + "/" + user.role();
        }
        return authentication.getName() == null ? "anonymous" : authentication.getName();
    }

    private String remoteAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
