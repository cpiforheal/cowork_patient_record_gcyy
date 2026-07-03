package com.coshare.patientrecord.security;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public final class AuthPermission {

    private AuthPermission() {}

    public static SessionUser currentUserOrThrow() {
        SessionUser user = currentUser();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        return user;
    }

    public static void requireAnyRole(String message, String... roles) {
        SessionUser user = currentUserOrThrow();
        Set<String> allowedRoles = Set.of(roles);
        if (!allowedRoles.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }

    public static SessionUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionUser user)) {
            return null;
        }
        return user;
    }
}
