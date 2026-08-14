package com.coshare.patientrecord.auth.controller;

import com.coshare.patientrecord.auth.dto.ActiveDepartmentRequest;
import com.coshare.patientrecord.auth.dto.AccountSummary;
import com.coshare.patientrecord.auth.dto.AccountUpsertRequest;
import com.coshare.patientrecord.auth.dto.DepartmentOption;
import com.coshare.patientrecord.auth.dto.DirectoryAccountOption;
import com.coshare.patientrecord.auth.dto.LoginAccountOptions;
import com.coshare.patientrecord.auth.dto.LoginOptions;
import com.coshare.patientrecord.auth.dto.LoginRequest;
import com.coshare.patientrecord.auth.dto.LoginResult;
import com.coshare.patientrecord.auth.dto.NavigationResult;
import com.coshare.patientrecord.auth.dto.PasswordChangeRequest;
import com.coshare.patientrecord.auth.dto.PasswordResetRequest;
import com.coshare.patientrecord.auth.dto.RoleDescriptor;
import com.coshare.patientrecord.auth.service.AuthAccountAdminService;
import com.coshare.patientrecord.auth.service.AuthNavigationService;
import com.coshare.patientrecord.auth.service.PortalAuthenticationService;
import com.coshare.patientrecord.common.api.ApiResult;
import com.coshare.patientrecord.security.AuthPermission;
import com.coshare.patientrecord.security.AuthTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("mysql")
public class AuthApiController {

    private final PortalAuthenticationService portalAuthenticationService;
    private final AuthNavigationService authNavigationService;
    private final AuthAccountAdminService authAccountAdminService;

    public AuthApiController(
        PortalAuthenticationService portalAuthenticationService,
        AuthNavigationService authNavigationService,
        AuthAccountAdminService authAccountAdminService
    ) {
        this.portalAuthenticationService = portalAuthenticationService;
        this.authNavigationService = authNavigationService;
        this.authAccountAdminService = authAccountAdminService;
    }

    @PostMapping("/auth/login")
    public ApiResult<LoginResult> login(
        @RequestBody LoginRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResult.success(portalAuthenticationService.login(request, clientIp(servletRequest)));
    }

    @GetMapping("/auth/options")
    public ApiResult<LoginOptions> options() {
        return ApiResult.success(portalAuthenticationService.loginOptions());
    }

    @GetMapping("/auth/options/accounts")
    public ApiResult<LoginAccountOptions> accounts(@RequestParam String department) {
        return ApiResult.success(portalAuthenticationService.loginAccounts(department));
    }

    @PostMapping("/auth/logout")
    public ApiResult<Map<String, Boolean>> logout(HttpServletRequest request) {
        portalAuthenticationService.logout(AuthTokenFilter.extractToken(request));
        return ApiResult.success(Map.of("ok", true));
    }

    @GetMapping("/auth/navigation")
    public ApiResult<NavigationResult> navigation() {
        return ApiResult.success(authNavigationService.navigationFor(AuthPermission.currentUserOrThrow()));
    }

    @PostMapping("/auth/active-department")
    public ApiResult<DepartmentOption> switchActiveDepartment(
        @RequestBody ActiveDepartmentRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResult.success(portalAuthenticationService.switchActiveDepartment(
            AuthTokenFilter.extractToken(servletRequest),
            AuthPermission.currentUserOrThrow(),
            request == null ? "" : request.departmentId()
        ));
    }

    @PostMapping("/auth/password")
    public ApiResult<Map<String, Boolean>> changePassword(@RequestBody PasswordChangeRequest request) {
        portalAuthenticationService.changePassword(AuthPermission.currentUserOrThrow(), request);
        return ApiResult.success(Map.of("ok", true));
    }

    @GetMapping("/auth/directory/accounts")
    public ApiResult<List<DirectoryAccountOption>> directoryAccounts(
        @RequestParam(required = false, defaultValue = "") String department
    ) {
        AuthPermission.requireAnyRole(
            "当前岗位无人员目录访问权限",
            "admin", "quality", "frontdesk", "reception", "inspection", "tcm", "doctor", "nurse", "lab", "ecg", "ultrasound"
        );
        var user = AuthPermission.currentUserOrThrow();
        return ApiResult.success(authAccountAdminService.directoryAccounts(user, department));
    }

    @GetMapping("/auth/admin/roles")
    public ApiResult<List<RoleDescriptor>> roles() {
        requireAdministrator();
        return ApiResult.success(authAccountAdminService.roles());
    }

    @GetMapping("/auth/admin/accounts")
    public ApiResult<List<AccountSummary>> accounts() {
        requireAdministrator();
        return ApiResult.success(authAccountAdminService.accounts());
    }

    @PostMapping("/auth/admin/accounts")
    public ApiResult<AccountSummary> createAccount(@RequestBody AccountUpsertRequest request) {
        requireAdministrator();
        return ApiResult.success(authAccountAdminService.create(request));
    }

    @PostMapping("/auth/admin/accounts/department-test-batch")
    public ApiResult<List<AccountSummary>> createDepartmentTestAccounts() {
        requireAdministrator();
        return ApiResult.success(authAccountAdminService.createDepartmentTestAccounts(AuthPermission.currentUserOrThrow().id()));
    }

    @PutMapping("/auth/admin/accounts/{accountId}")
    public ApiResult<AccountSummary> updateAccount(
        @PathVariable String accountId,
        @RequestBody AccountUpsertRequest request
    ) {
        requireAdministrator();
        return ApiResult.success(authAccountAdminService.update(accountId, request, AuthPermission.currentUserOrThrow().id()));
    }

    @PostMapping("/auth/admin/accounts/{accountId}/password")
    public ApiResult<Map<String, Boolean>> resetPassword(
        @PathVariable String accountId,
        @RequestBody PasswordResetRequest request
    ) {
        requireAdministrator();
        authAccountAdminService.resetPassword(accountId, request);
        return ApiResult.success(Map.of("ok", true));
    }

    @DeleteMapping("/auth/admin/accounts/{accountId}")
    public ApiResult<Map<String, Boolean>> deleteAccount(@PathVariable String accountId) {
        var operator = AuthPermission.currentUserOrThrow();
        requireAdministrator();
        authAccountAdminService.delete(accountId, operator.id());
        return ApiResult.success(Map.of("ok", true));
    }

    private void requireAdministrator() {
        AuthPermission.requireAnyRole("当前账号无系统管理权限", "admin");
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
