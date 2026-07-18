package com.coshare.patientrecord.security;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class InventoryPermission {

    private static final Set<String> APPROVER_ROLES = Set.of("admin", "quality");
    private static final Set<String> COUNTER_ROLES = Set.of("admin", "quality");
    private static final Set<String> STAFF_ROLES = Set.of(
        "admin", "quality", "nurse", "nursing", "doctor", "frontdesk", "reception", "lab", "ecg", "ultrasound", "inspection"
    );

    private InventoryPermission() {}

    public static SessionUser requireAdmin() {
        SessionUser user = currentUserOrThrow();
        if (!"admin".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无进销存管理权限");
        }
        return user;
    }

    public static SessionUser requireApprover() {
        SessionUser user = currentUserOrThrow();
        if (!APPROVER_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无进销存审核权限");
        }
        return user;
    }

    public static SessionUser requireCounter() {
        SessionUser user = currentUserOrThrow();
        if (!COUNTER_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无进销存盘点权限");
        }
        return user;
    }

    public static SessionUser requireStockKeeper() {
        SessionUser user = currentUserOrThrow();
        if (!APPROVER_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无物资档案及库存维护权限");
        }
        return user;
    }

    public static SessionUser requireStaff() {
        SessionUser user = currentUserOrThrow();
        if (!STAFF_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号无进销存操作权限");
        }
        return user;
    }

    public static void requireAuthenticated() {
        currentUserOrThrow();
    }

    public static SessionUser currentUserOrThrow() {
        return AuthPermission.currentUserOrThrow();
    }
}
