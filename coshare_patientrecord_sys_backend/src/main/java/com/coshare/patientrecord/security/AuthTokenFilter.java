package com.coshare.patientrecord.security;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("mysql")
public class AuthTokenFilter extends OncePerRequestFilter {

    private final AuthSessionService authSessionService;
    private final ObjectMapper objectMapper;

    public AuthTokenFilter(AuthSessionService authSessionService, ObjectMapper objectMapper) {
        this.authSessionService = authSessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/health/db")
            && !path.equals("/auth/logout")
            && !path.equals("/auth/password")
            && !path.startsWith("/clinic-api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        var sessionUser = authSessionService.authenticate(extractToken(request));
        if (sessionUser.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            var payload = new LinkedHashMap<String, Object>();
            payload.put("code", 401);
            payload.put("msg", "登录已失效，请重新登录");
            payload.put("data", null);
            objectMapper.writeValue(response.getWriter(), payload);
            return;
        }

        SessionUser user = sessionUser.get();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            user,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    public static String extractToken(HttpServletRequest request) {
        String token = request.getHeader("x-access-token");
        if (token != null && !token.isBlank()) return token.trim();
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return "";
    }
}
