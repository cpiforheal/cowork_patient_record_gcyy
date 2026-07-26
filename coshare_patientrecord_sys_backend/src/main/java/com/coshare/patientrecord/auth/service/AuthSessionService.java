package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.LoginAccountOptions;
import com.coshare.patientrecord.auth.dto.LoginOptions;
import com.coshare.patientrecord.auth.dto.LoginRequest;
import com.coshare.patientrecord.auth.dto.LoginResult;
import com.coshare.patientrecord.auth.dto.PasswordChangeRequest;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.dto.DepartmentOption;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Profile("mysql")
@DependsOn("clinicDatabaseRepository")
public class AuthSessionService {

    private static final Duration TOKEN_TTL = Duration.ofHours(12);
    /** 展示终端（候诊大屏）专用长效会话：无人值守设备无法定期重新登录。 */
    private static final String DISPLAY_ROLE = "display";
    private static final Duration DISPLAY_TOKEN_TTL = Duration.ofDays(30);
    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOGIN_LOCK_TTL = Duration.ofMinutes(10);
    private static final Duration LOGIN_HANDLE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final String bootstrapAdminPassword;
    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] loginHandleSecret = new byte[32];
    private final Map<String, LoginHandle> loginHandles = new ConcurrentHashMap<>();

    public AuthSessionService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        PasswordEncoder passwordEncoder,
        Environment environment,
        @Value("${clinic.bootstrap.admin-password:}") String bootstrapAdminPassword
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.bootstrapAdminPassword = bootstrapAdminPassword == null ? "" : bootstrapAdminPassword.trim();
        secureRandom.nextBytes(loginHandleSecret);
    }

    @PostConstruct
    public void initializeAuthentication() {
        ensureBootstrapAdministrator();
    }

    private void ensureBootstrapAdministrator() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_accounts", Integer.class);
        if (count != null && count > 0) return;
        if (bootstrapAdminPassword.isBlank()) {
            if (isProduction()) {
                throw new IllegalStateException("空库首次启动必须配置 CLINIC_BOOTSTRAP_ADMIN_PASSWORD");
            }
            log.warn("Empty account database: set CLINIC_BOOTSTRAP_ADMIN_PASSWORD to create the one-time administrator");
            return;
        }

        String departmentId = "bootstrap-department-information";
        ObjectNode department = objectMapper.createObjectNode();
        department.put("id", departmentId);
        department.put("code", "INFORMATION");
        department.put("name", "信息科");
        department.put("status", "ACTIVE");
        department.put("scope", "首次启动管理员所属科室");
        jdbcTemplate.update(
            "INSERT INTO clinic_departments (id, code, name, status, raw_json) VALUES (?, ?, ?, 'ACTIVE', CAST(? AS JSON))",
            departmentId,
            "INFORMATION",
            "信息科",
            toJson(department)
        );

        ObjectNode account = objectMapper.createObjectNode();
        account.put("id", "bootstrap-admin");
        account.put("username", "admin");
        account.put("passwordHash", passwordEncoder.encode(bootstrapAdminPassword));
        account.put("mustChangePassword", true);
        account.put("name", "首次启动管理员");
        account.put("department", "信息科");
        account.put("role", "admin");
        account.put("roleLabel", "系统管理员");
        account.put("scope", "首次启动引导账号；首次登录后必须修改密码");
        account.put("status", "启用");
        account.put("createdAt", Instant.now().toString());
        account.put("updatedAt", Instant.now().toString());
        jdbcTemplate.update(
            "INSERT INTO clinic_accounts (id, username, role, status, raw_json) VALUES (?, ?, ?, ?, ?)",
            "bootstrap-admin",
            "admin",
            "admin",
            "启用",
            toJson(account)
        );
        jdbcTemplate.update(
            "INSERT INTO clinic_account_departments (account_id, department_id, is_primary, status) VALUES (?, ?, TRUE, 'ACTIVE')",
            "bootstrap-admin",
            departmentId
        );
    }

    public LoginResult login(LoginRequest request) {
        return login(request, "");
    }

    public LoginResult login(LoginRequest request, String remoteAddress) {
        cleanupAuthenticationState();
        String accountHandle = request == null || request.accountHandle() == null ? "" : request.accountHandle().trim();
        String loginIdentity = request == null ? "" : normalize(request.username());
        if ((loginIdentity.isBlank() && accountHandle.isBlank()) || request.password() == null || request.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号和密码");
        }

        Optional<JsonNode> candidate = accountHandle.isBlank()
            ? loadAccount(loginIdentity)
            : resolveLoginHandle(accountHandle).flatMap(this::loadAccountById);
        String username = candidate.map(account -> normalize(text(account, "username"))).orElse(loginIdentity);
        String failureKey = (username.isBlank() ? "invalid-handle" : username) + "|" + normalize(remoteAddress);
        rejectIfLocked(failureKey);
        JsonNode account = candidate
            .orElseThrow(() -> {
                registerFailedLogin(failureKey);
                return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在或未启用");
            });
        if (!"启用".equals(text(account, "status", "启用"))) {
            registerFailedLogin(failureKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用，请联系管理员");
        }
        String storedPassword = text(account, "passwordHash");
        if (storedPassword.isBlank()) {
            registerFailedLogin(failureKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号未初始化密码，请联系管理员");
        }
        if (!isBcrypt(storedPassword)) {
            registerFailedLogin(failureKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号密码未完成安全升级，请联系管理员重置密码");
        }
        if (!passwordEncoder.matches(request.password(), storedPassword)) {
            registerFailedLogin(failureKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密码错误");
        }
        clearFailedLogin(failureKey);

        String accountRole = RoleCatalog.canonicalize(text(account, "role"));
        if (!RoleCatalog.isCanonical(accountRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号尚未配置岗位角色，请联系系统管理员");
        }
        DepartmentIdentity department = resolveLoginDepartment(text(account, "id"));
        SessionUser user = new SessionUser(
            text(account, "id"),
            text(account, "username"),
            text(account, "name", text(account, "username")),
            accountRole,
            RoleCatalog.label(accountRole),
            department.id(),
            department.name(),
            account.path("mustChangePassword").asBoolean(false),
            Instant.now().plus(tokenTtlForRole(accountRole))
        );
        String token = newToken();
        storeSession(token, user);
        return new LoginResult(token, Map.of(
            "name", user.name(),
            "role", user.role(),
            "roleLabel", user.roleLabel(),
            "activeDepartmentId", user.activeDepartmentId(),
            "department", user.department()
        ), account.path("mustChangePassword").asBoolean(false));
    }

    public Optional<SessionUser> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        List<SessionUser> users = jdbcTemplate.query(
            """
            SELECT s.user_id, s.username, s.display_name, s.role, s.role_label,
                   s.active_department_id, d.name AS department, s.must_change_password, s.expires_at
            FROM clinic_auth_sessions s
            JOIN clinic_accounts a
              ON a.id = s.user_id
             AND a.status = '启用'
             AND LOWER(a.role) = LOWER(s.role)
            JOIN clinic_account_departments ad
              ON ad.account_id = s.user_id
             AND ad.department_id = s.active_department_id
             AND ad.status = 'ACTIVE'
            JOIN clinic_departments d
              ON d.id = s.active_department_id
             AND d.status = 'ACTIVE'
            WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > CURRENT_TIMESTAMP(6)
            LIMIT 1
            """,
            (resultSet, rowNum) -> new SessionUser(
                resultSet.getString("user_id"),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("role"),
                resultSet.getString("role_label"),
                resultSet.getString("active_department_id"),
                resultSet.getString("department"),
                resultSet.getBoolean("must_change_password"),
                resultSet.getTimestamp("expires_at").toInstant()
            ),
            sha256(token)
        );
        Optional<SessionUser> user = users.stream().findFirst();
        user.ifPresent(value -> renewDisplaySessionIfNeeded(token, value));
        return user;
    }

    /**
     * 展示终端会话滑动续期：剩余有效期不足一半时后移过期时间，
     * 保证常亮大屏只要在持续轮询就不会掉线。普通岗位账号维持固定 12 小时语义。
     */
    private void renewDisplaySessionIfNeeded(String token, SessionUser user) {
        if (!isDisplayRole(user.role())) return;
        if (user.expiresAt().isAfter(Instant.now().plus(DISPLAY_TOKEN_TTL.dividedBy(2)))) return;
        jdbcTemplate.update(
            "UPDATE clinic_auth_sessions SET expires_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            Timestamp.from(Instant.now().plus(DISPLAY_TOKEN_TTL)),
            sha256(token)
        );
    }

    static Duration tokenTtlForRole(String role) {
        return isDisplayRole(role) ? DISPLAY_TOKEN_TTL : TOKEN_TTL;
    }

    static boolean isDisplayRole(String role) {
        return role != null && DISPLAY_ROLE.equalsIgnoreCase(role.trim());
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        jdbcTemplate.update(
            "UPDATE clinic_auth_sessions SET revoked_at = CURRENT_TIMESTAMP(6), revoke_reason = 'logout' WHERE token_hash = ? AND revoked_at IS NULL",
            sha256(token)
        );
    }

    @Transactional
    public DepartmentOption switchActiveDepartment(String token, SessionUser user, String departmentId) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
        String targetId = departmentId == null ? "" : departmentId.trim();
        if (targetId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择活动科室");
        List<DepartmentOption> matches = jdbcTemplate.query(
            """
            SELECT d.id, d.code, d.name, d.status, ad.is_primary
            FROM clinic_account_departments ad
            JOIN clinic_departments d ON d.id = ad.department_id
            WHERE ad.account_id = ? AND ad.department_id = ?
              AND ad.status = 'ACTIVE' AND d.status = 'ACTIVE'
            LIMIT 1
            """,
            (rs, rowNum) -> new DepartmentOption(
                rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("is_primary"), rs.getString("status")
            ),
            user.id(),
            targetId
        );
        DepartmentOption target = matches.stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号未获授权使用该科室"));
        int updated = jdbcTemplate.update(
            """
            UPDATE clinic_auth_sessions
            SET active_department_id = ?, department = ?
            WHERE token_hash = ? AND user_id = ? AND revoked_at IS NULL
            """,
            target.id(), target.name(), sha256(token == null ? "" : token), user.id()
        );
        if (updated != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");

        String auditId = "audit-auth-department-" + java.util.UUID.randomUUID();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("id", auditId);
        raw.put("time", Instant.now().toString());
        raw.put("operator", user.name());
        raw.put("role", user.roleLabel());
        raw.put("module", "system");
        raw.put("action", "切换活动科室");
        raw.put("actionCode", "auth.active-department.switch");
        raw.put("targetType", "department");
        raw.put("targetKey", target.id());
        raw.put("targetLabel", target.name());
        raw.put("beforeValue", user.department());
        raw.put("afterValue", target.name());
        raw.put("result", "成功");
        jdbcTemplate.update(
            """
            INSERT INTO clinic_audit_logs (id, time, operator, role, patient, patient_id, module, action, result, raw_json)
            VALUES (?, ?, ?, ?, '-', '', 'system', '切换活动科室', '成功', CAST(? AS JSON))
            """,
            auditId, Instant.now().toString(), user.name(), user.roleLabel(), toJson(raw)
        );
        return target;
    }

    @Transactional
    public void changePassword(SessionUser user, PasswordChangeRequest request) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login expired");
        }
        String newPassword = request.newPassword() == null ? "" : request.newPassword().trim();
        if (newPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password is required");
        }
        if (newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 8 characters");
        }

        JsonNode account = loadAccount(user.username())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        ObjectNode updated = ((ObjectNode) account).deepCopy();
        updated.remove(List.of("password", "currentPassword"));
        updated.put("passwordHash", passwordEncoder.encode(newPassword));
        updated.put("mustChangePassword", false);
        updated.put("updatedAt", Instant.now().toString());
        jdbcTemplate.update("UPDATE clinic_accounts SET raw_json = ? WHERE id = ?", toJson(updated), text(updated, "id"));
        revokeAllSessions(user.id(), "password_changed");
    }

    public LoginOptions loginOptions() {
        cleanupLoginHandles();
        List<ObjectNode> accounts = loadEnabledAccounts();
        List<String> departments = new ArrayList<>();
        for (JsonNode account : accounts) {
            String department = text(account, "department");
            if (!department.isBlank() && !departments.contains(department)) {
                departments.add(department);
            }
        }
        departments.sort(String::compareTo);
        return new LoginOptions(departments, accounts.stream().map(this::toLoginAccountOption).toList());
    }

    public LoginAccountOptions loginAccounts(String department) {
        cleanupLoginHandles();
        String normalizedDepartment = department == null ? "" : department.trim();
        List<Map<String, String>> accountOptions = new ArrayList<>();
        for (JsonNode account : loadEnabledAccounts()) {
            String accountDepartment = text(account, "department");
            if (!normalizedDepartment.isBlank() && !normalizedDepartment.equals(accountDepartment)) continue;
            accountOptions.add(toLoginAccountOption(account));
        }
        return new LoginAccountOptions(accountOptions);
    }

    private Map<String, String> toLoginAccountOption(JsonNode account) {
        String accountId = text(account, "id");
        return Map.of(
            "accountHandle", issueLoginHandle(accountId),
            "label", maskedLabel(account),
            "department", text(account, "department")
        );
    }

    private Optional<JsonNode> loadAccount(String username) {
        return jdbcTemplate.query(
            "SELECT id, username, role, status, raw_json FROM clinic_accounts WHERE LOWER(username) = ? OR id = ? LIMIT 1",
            resultSet -> {
                if (!resultSet.next()) return Optional.empty();
                ObjectNode account = readJson(resultSet.getString("raw_json"));
                account.put("id", resultSet.getString("id"));
                account.put("username", resultSet.getString("username"));
                account.put("role", resultSet.getString("role"));
                account.put("status", resultSet.getString("status"));
                return Optional.of(account);
            },
            username,
            username
        );
    }

    private Optional<JsonNode> loadAccountById(String accountId) {
        return jdbcTemplate.query(
            "SELECT id, username, role, status, raw_json FROM clinic_accounts WHERE id = ? LIMIT 1",
            resultSet -> {
                if (!resultSet.next()) return Optional.empty();
                ObjectNode account = readJson(resultSet.getString("raw_json"));
                account.put("id", resultSet.getString("id"));
                account.put("username", resultSet.getString("username"));
                account.put("role", resultSet.getString("role"));
                account.put("status", resultSet.getString("status"));
                return Optional.of(account);
            },
            accountId
        );
    }

    private List<ObjectNode> loadEnabledAccounts() {
        return jdbcTemplate.query(
            "SELECT id, username, role, status, raw_json FROM clinic_accounts ORDER BY username",
            (resultSet, rowNum) -> {
                ObjectNode account = readJson(resultSet.getString("raw_json"));
                account.put("id", resultSet.getString("id"));
                account.put("username", resultSet.getString("username"));
                account.put("role", resultSet.getString("role"));
                account.put("status", resultSet.getString("status"));
                return account;
            }
        ).stream()
            .filter(account -> "启用".equals(text(account, "status", "启用")))
            .filter(account -> RoleCatalog.find(text(account, "role")).isPresent())
            .sorted(Comparator
                .comparing((ObjectNode account) -> text(account, "department"))
                .thenComparing(account -> text(account, "name", text(account, "username")))
                .thenComparing(account -> text(account, "username", text(account, "id"))))
            .toList();
    }

    private String issueLoginHandle(String accountId) {
        String nonce = newToken();
        Instant expiresAt = Instant.ofEpochSecond(Instant.now().plus(LOGIN_HANDLE_TTL).getEpochSecond());
        String payload = nonce + "." + expiresAt.getEpochSecond();
        loginHandles.put(nonce, new LoginHandle(accountId, expiresAt));
        return payload + "." + signLoginHandle(payload);
    }

    private Optional<String> resolveLoginHandle(String handle) {
        String[] parts = handle.split("\\.", -1);
        if (parts.length != 3) return Optional.empty();
        String payload = parts[0] + "." + parts[1];
        String expectedSignature = signLoginHandle(payload);
        if (!MessageDigest.isEqual(
            expectedSignature.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            parts[2].getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        )) return Optional.empty();
        long epochSecond;
        try {
            epochSecond = Long.parseLong(parts[1]);
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
        LoginHandle value = loginHandles.get(parts[0]);
        Instant signedExpiry = Instant.ofEpochSecond(epochSecond);
        if (value == null || !value.expiresAt().equals(signedExpiry) || !signedExpiry.isAfter(Instant.now())) {
            loginHandles.remove(parts[0]);
            return Optional.empty();
        }
        return Optional.of(value.accountId());
    }

    private String signLoginHandle(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(loginHandleSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", error);
        }
    }

    private void cleanupLoginHandles() {
        Instant now = Instant.now();
        loginHandles.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String maskedLabel(JsonNode account) {
        String name = text(account, "name");
        String masked = name.isBlank() ? "岗位账号" : name.substring(0, 1) + "**";
        String roleLabel = RoleCatalog.label(text(account, "role"));
        return roleLabel.isBlank() ? masked : masked + "（" + roleLabel + "）";
    }

    private ObjectNode readJson(String rawJson) throws SQLException {
        try {
            return (ObjectNode) objectMapper.readTree(rawJson);
        } catch (Exception error) {
            throw new SQLException("账号数据解析失败", error);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void cleanupAuthenticationState() {
        jdbcTemplate.update(
            "DELETE FROM clinic_auth_sessions WHERE expires_at < CURRENT_TIMESTAMP(6) OR revoked_at < DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY)"
        );
        jdbcTemplate.update(
            "DELETE FROM clinic_login_failures WHERE last_failed_at < DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)"
        );
    }

    private void storeSession(String token, SessionUser user) {
        jdbcTemplate.update(
            """
            INSERT INTO clinic_auth_sessions (
              token_hash, user_id, username, display_name, role, role_label, department, active_department_id,
              must_change_password, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?, ?)
            """,
            sha256(token), user.id(), user.username(), user.name(), user.role(), user.roleLabel(), user.department(),
            user.activeDepartmentId(), user.mustChangePassword(),
            Timestamp.from(user.expiresAt())
        );
    }

    private DepartmentIdentity resolveLoginDepartment(String accountId) {
        List<DepartmentIdentity> rows = jdbcTemplate.query(
            """
            SELECT d.id, d.name
            FROM clinic_account_departments ad
            JOIN clinic_departments d ON d.id = ad.department_id
            WHERE ad.account_id = ? AND ad.status = 'ACTIVE' AND d.status = 'ACTIVE'
            ORDER BY ad.is_primary DESC, d.name, d.id
            LIMIT 1
            """,
            (rs, rowNum) -> new DepartmentIdentity(rs.getString("id"), rs.getString("name")),
            accountId
        );
        return rows.stream().findFirst().orElseThrow(
            () -> new ResponseStatusException(HttpStatus.FORBIDDEN, "账号未关联有效科室，请联系系统管理员")
        );
    }

    public void revokeAllSessions(String userId, String reason) {
        if (userId == null || userId.isBlank()) return;
        jdbcTemplate.update(
            """
            UPDATE clinic_auth_sessions
            SET revoked_at = CURRENT_TIMESTAMP(6), revoke_reason = ?
            WHERE user_id = ? AND revoked_at IS NULL
            """,
            reason,
            userId
        );
    }

    private void rejectIfLocked(String failureKey) {
        List<Timestamp> values = jdbcTemplate.query(
            "SELECT locked_until FROM clinic_login_failures WHERE failure_key_hash = ? AND locked_until IS NOT NULL",
            (resultSet, rowNum) -> resultSet.getTimestamp("locked_until"),
            sha256(failureKey)
        );
        if (!values.isEmpty() && values.get(0).toInstant().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请稍后再试");
        }
    }

    private void registerFailedLogin(String failureKey) {
        Instant now = Instant.now();
        List<LoginFailure> existingRows = jdbcTemplate.query(
            "SELECT attempts, locked_until, last_failed_at FROM clinic_login_failures WHERE failure_key_hash = ?",
            (resultSet, rowNum) -> new LoginFailure(
                resultSet.getInt("attempts"),
                instant(resultSet.getTimestamp("locked_until")),
                resultSet.getTimestamp("last_failed_at").toInstant()
            ),
            sha256(failureKey)
        );
        LoginFailure existing = existingRows.stream().findFirst().orElse(null);
        int attempts = existing == null || existing.isExpired(now) ? 1 : existing.attempts() + 1;
        Instant lockedUntil = attempts >= MAX_FAILED_ATTEMPTS ? now.plus(LOGIN_LOCK_TTL) : null;
        int separator = failureKey.indexOf('|');
        String username = separator < 0 ? failureKey : failureKey.substring(0, separator);
        String remoteAddress = separator < 0 ? "" : failureKey.substring(separator + 1);
        jdbcTemplate.update(
            """
            INSERT INTO clinic_login_failures (
              failure_key_hash, username, remote_address, attempts, locked_until, last_failed_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE attempts = VALUES(attempts), locked_until = VALUES(locked_until),
              last_failed_at = VALUES(last_failed_at), remote_address = VALUES(remote_address)
            """,
            sha256(failureKey), username, remoteAddress, attempts,
            lockedUntil == null ? null : Timestamp.from(lockedUntil), Timestamp.from(now)
        );
    }

    private void clearFailedLogin(String failureKey) {
        jdbcTemplate.update("DELETE FROM clinic_login_failures WHERE failure_key_hash = ?", sha256(failureKey));
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
    }

    private boolean isBcrypt(String value) {
        return value != null && (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize account", error);
        }
    }

    private String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private record LoginFailure(int attempts, Instant lockedUntil, Instant lastFailedAt) {
        boolean isExpired(Instant now) {
            return lockedUntil != null && lockedUntil.isBefore(now)
                || lockedUntil == null && lastFailedAt.plus(LOGIN_LOCK_TTL).isBefore(now);
        }
    }

    private record DepartmentIdentity(String id, String name) {}

    private record LoginHandle(String accountId, Instant expiresAt) {}

}
