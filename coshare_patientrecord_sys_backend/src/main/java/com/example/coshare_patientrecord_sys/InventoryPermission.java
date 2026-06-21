package com.example.coshare_patientrecord_sys;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class InventoryPermission {

    private static final Set<String> APPROVER_ROLES = Set.of("admin", "quality");
    private static final Set<String> COUNTER_ROLES = Set.of("admin", "quality");
    private static final Set<String> STAFF_ROLES = Set.of(
        "admin", "quality", "nurse", "doctor", "frontdesk", "lab", "ecg", "ultrasound"
    );

    private InventoryPermission() {}

    public static AuthSessionService.SessionUser requireAdmin() {
        AuthSessionService.SessionUser user = currentUserOrThrow();
        if (!"admin".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无进销存管理权限");
        }
        return user;
    }

    public static AuthSessionService.SessionUser requireApprover() {
        AuthSessionService.SessionUser user = currentUserOrThrow();
        if (!APPROVER_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无进销存审核权限");
        }
        return user;
    }

    public static AuthSessionService.SessionUser requireCounter() {
        AuthSessionService.SessionUser user = currentUserOrThrow();
        if (!COUNTER_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无进销存盘点权限");
        }
        return user;
    }

    public static AuthSessionService.SessionUser requireStockKeeper() {
        return requireAdmin();
    }

    public static AuthSessionService.SessionUser requireStaff() {
        AuthSessionService.SessionUser user = currentUserOrThrow();
        if (!STAFF_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无进销存操作权限");
        }
        return user;
    }

    public static void requireAuthenticated() {
        currentUserOrThrow();
    }

    public static AuthSessionService.SessionUser currentUserOrThrow() {
        return AuthPermission.currentUserOrThrow();
    }
}
