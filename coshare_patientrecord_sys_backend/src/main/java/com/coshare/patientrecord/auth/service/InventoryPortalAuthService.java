package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.LoginAccountOptions;
import com.coshare.patientrecord.auth.dto.LoginOptions;
import com.coshare.patientrecord.auth.dto.LoginRequest;
import com.coshare.patientrecord.auth.dto.LoginResult;
import com.coshare.patientrecord.auth.dto.PasswordChangeRequest;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.config.PortalMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Authentication and sessions reserved for the standalone inventory portal. */
@Service
@Profile("mysql")
public class InventoryPortalAuthService {
    private static final Duration TOKEN_TTL = Duration.ofHours(12);
    private static final Duration LOGIN_HANDLE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PortalMode portalMode;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, LoginHandle> loginHandles = new ConcurrentHashMap<>();

    public InventoryPortalAuthService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, PortalMode portalMode) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.portalMode = portalMode;
    }

    @Transactional
    public LoginResult login(LoginRequest request, String remoteAddress) {
        requireInventoryPortal();
        cleanupHandles();
        if (request == null || request.password() == null || request.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号和密码");
        }
        String handle = request.accountHandle() == null ? "" : request.accountHandle().trim();
        String username = normalize(request.username());
        if (handle.isBlank() && username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择账号");
        }
        String accountId = handle.isBlank() ? "" : resolveHandle(handle).orElse("");
        Account account = findAccount(accountId.isBlank() ? username : accountId, accountId.isBlank());
        if (account == null || !"启用".equals(account.status())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        SessionUser user = toSessionUser(account, Instant.now().plus(TOKEN_TTL));
        String token = newToken();
        jdbcTemplate.update(
            "INSERT INTO inventory_portal_sessions "
                + "(token_hash, user_id, username, display_name, role, role_label, active_department_id, department_name, must_change_password, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            sha256(token), user.id(), user.username(), user.name(), user.role(), user.roleLabel(),
            user.activeDepartmentId(), user.department(), user.mustChangePassword(), Timestamp.from(user.expiresAt())
        );
        return loginResult(token, user);
    }

    @Transactional(readOnly = true)
    public Optional<SessionUser> authenticate(String token) {
        requireInventoryPortal();
        if (token == null || token.isBlank()) return Optional.empty();
        List<SessionUser> users = jdbcTemplate.query(
            "SELECT s.user_id, s.username, s.display_name, s.role, s.role_label, s.active_department_id, "
                + "s.department_name, s.must_change_password, s.expires_at "
                + "FROM inventory_portal_sessions s "
                + "JOIN inventory_portal_accounts a ON a.id = s.user_id AND a.status = '启用' "
                + "WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > CURRENT_TIMESTAMP(6) LIMIT 1",
            (resultSet, rowNum) -> new SessionUser(
                resultSet.getString("user_id"), resultSet.getString("username"), resultSet.getString("display_name"),
                resultSet.getString("role"), resultSet.getString("role_label"), resultSet.getString("active_department_id"),
                resultSet.getString("department_name"), resultSet.getBoolean("must_change_password"),
                resultSet.getTimestamp("expires_at").toInstant()
            ),
            sha256(token)
        );
        return users.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public LoginOptions loginOptions() {
        requireInventoryPortal();
        List<Account> accounts = enabledAccounts();
        List<String> departments = new ArrayList<>();
        for (Account account : accounts) {
            if (!departments.contains(account.departmentName())) departments.add(account.departmentName());
        }
        return new LoginOptions(List.copyOf(departments), accountOptions(accounts));
    }

    @Transactional(readOnly = true)
    public LoginAccountOptions loginAccounts(String department) {
        requireInventoryPortal();
        String selectedDepartment = department == null ? "" : department.trim();
        List<Account> accounts = enabledAccounts().stream()
            .filter(account -> selectedDepartment.isBlank() || selectedDepartment.equals(account.departmentName()))
            .toList();
        return new LoginAccountOptions(accountOptions(accounts));
    }

    @Transactional
    public void logout(String token) {
        requireInventoryPortal();
        if (token == null || token.isBlank()) return;
        jdbcTemplate.update(
            "UPDATE inventory_portal_sessions SET revoked_at = CURRENT_TIMESTAMP(6), revoke_reason = 'logout' "
                + "WHERE token_hash = ? AND revoked_at IS NULL",
            sha256(token)
        );
    }

    @Transactional
    public void changePassword(SessionUser user, PasswordChangeRequest request) {
        requireInventoryPortal();
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        String password = request == null || request.newPassword() == null ? "" : request.newPassword();
        if (password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少需要 8 位");
        }
        int changed = jdbcTemplate.update(
            "UPDATE inventory_portal_accounts SET password_hash = ?, must_change_password = FALSE, updated_at = CURRENT_TIMESTAMP(6) "
                + "WHERE id = ? AND status = '启用'",
            passwordEncoder.encode(password), user.id()
        );
        if (changed != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已不可用");
        jdbcTemplate.update(
            "UPDATE inventory_portal_sessions SET revoked_at = CURRENT_TIMESTAMP(6), revoke_reason = 'password_changed' "
                + "WHERE user_id = ? AND revoked_at IS NULL",
            user.id()
        );
    }

    private Account findAccount(String value, boolean byUsername) {
        String sql = "SELECT id, username, display_name, department_key, department_name, clinic_role, password_hash, "
            + "must_change_password, status FROM inventory_portal_accounts WHERE "
            + (byUsername ? "LOWER(username) = ?" : "id = ?") + " LIMIT 1";
        return jdbcTemplate.query(sql, resultSet -> resultSet.next() ? account(resultSet) : null, value);
    }

    private List<Account> enabledAccounts() {
        return jdbcTemplate.query(
            "SELECT id, username, display_name, department_key, department_name, clinic_role, password_hash, "
                + "must_change_password, status FROM inventory_portal_accounts WHERE status = '启用' ORDER BY display_order, id",
            (resultSet, rowNum) -> account(resultSet)
        );
    }

    private List<Map<String, String>> accountOptions(List<Account> accounts) {
        cleanupHandles();
        List<Map<String, String>> result = new ArrayList<>();
        for (Account account : accounts) {
            String handle = newHandle();
            loginHandles.put(handle, new LoginHandle(account.id(), Instant.now().plus(LOGIN_HANDLE_TTL)));
            Map<String, String> option = new LinkedHashMap<>();
            option.put("accountHandle", handle);
            option.put("label", "admin".equals(account.clinicRole()) ? "进销存管理员（管理端）" : account.displayName() + "（科室填报）");
            option.put("department", account.departmentName());
            result.add(Map.copyOf(option));
        }
        return List.copyOf(result);
    }

    private Account account(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new Account(
            resultSet.getString("id"), resultSet.getString("username"), resultSet.getString("display_name"),
            resultSet.getString("department_key"), resultSet.getString("department_name"), resultSet.getString("clinic_role"),
            resultSet.getString("password_hash"), resultSet.getBoolean("must_change_password"), resultSet.getString("status")
        );
    }

    private SessionUser toSessionUser(Account account, Instant expiresAt) {
        String roleLabel = "admin".equals(account.clinicRole()) ? "进销存管理员" : "科室填报";
        return new SessionUser(
            account.id(), account.username(), account.displayName(), account.clinicRole(), roleLabel,
            account.departmentKey(), account.departmentName(), account.mustChangePassword(), expiresAt
        );
    }

    private LoginResult loginResult(String token, SessionUser user) {
        return new LoginResult(token, Map.of(
            "name", user.name(), "role", user.role(), "roleLabel", user.roleLabel(),
            "activeDepartmentId", user.activeDepartmentId(), "department", user.department()
        ), user.mustChangePassword());
    }

    private Optional<String> resolveHandle(String handle) {
        LoginHandle value = loginHandles.get(handle);
        return value == null || value.expiresAt().isBefore(Instant.now()) ? Optional.empty() : Optional.of(value.accountId());
    }

    private void cleanupHandles() {
        loginHandles.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
    }

    private String newHandle() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void requireInventoryPortal() {
        if (!portalMode.isInventoryPortal()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该认证接口仅用于进销存门户");
        }
    }

    private record LoginHandle(String accountId, Instant expiresAt) {}
    private record Account(
        String id, String username, String displayName, String departmentKey, String departmentName,
        String clinicRole, String passwordHash, boolean mustChangePassword, String status
    ) {}
}
