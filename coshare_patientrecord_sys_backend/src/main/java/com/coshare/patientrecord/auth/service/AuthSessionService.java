package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.LoginAccountOptions;
import com.coshare.patientrecord.auth.dto.LoginOptions;
import com.coshare.patientrecord.auth.dto.LoginRequest;
import com.coshare.patientrecord.auth.dto.LoginResult;
import com.coshare.patientrecord.auth.dto.PasswordChangeRequest;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Profile("mysql")
public class AuthSessionService {

    private static final Duration TOKEN_TTL = Duration.ofHours(12);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOGIN_LOCK_TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, SessionUser> sessions = new ConcurrentHashMap<>();
    private final Map<String, LoginFailure> loginFailures = new ConcurrentHashMap<>();

    public AuthSessionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResult login(LoginRequest request) {
        return login(request, "");
    }

    public LoginResult login(LoginRequest request, String remoteAddress) {
        cleanupExpiredSessions();
        String username = normalize(request.username());
        if (username.isBlank() || request.password() == null || request.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号和密码");
        }

        String failureKey = username + "|" + normalize(remoteAddress);
        rejectIfLocked(failureKey);
        JsonNode account = loadAccount(username)
            .orElseThrow(() -> {
                registerFailedLogin(failureKey);
                return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在或未启用");
            });
        if (!"启用".equals(text(account, "status", "启用"))) {
            registerFailedLogin(failureKey);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用，请联系管理员");
        }
        String storedPassword = text(account, "passwordHash", text(account, "password"));
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
        loginFailures.remove(failureKey);

        SessionUser user = new SessionUser(
            text(account, "id"),
            text(account, "username"),
            text(account, "name", text(account, "username")),
            text(account, "role", "frontdesk"),
            text(account, "roleLabel", text(account, "role", "frontdesk")),
            text(account, "department"),
            Instant.now().plus(TOKEN_TTL)
        );
        String token = newToken();
        sessions.put(token, user);
        return new LoginResult(token, Map.of(
            "name", user.name(),
            "role", user.role(),
            "roleLabel", user.roleLabel(),
            "department", user.department()
        ));
    }

    public Optional<SessionUser> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        SessionUser user = sessions.get(token);
        if (user == null) return Optional.empty();
        if (user.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) sessions.remove(token);
    }

    @Transactional
    public void changePassword(SessionUser user, PasswordChangeRequest request) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login expired");
        }
        String currentPassword = request.currentPassword() == null ? "" : request.currentPassword();
        String newPassword = request.newPassword() == null ? "" : request.newPassword().trim();
        if (currentPassword.isBlank() || newPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password and new password are required");
        }
        if (newPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 6 characters");
        }

        JsonNode account = loadAccount(user.username())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        String storedPassword = text(account, "passwordHash", text(account, "password"));
        if (storedPassword.isBlank() || !isBcrypt(storedPassword) || !passwordEncoder.matches(currentPassword, storedPassword)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }

        ObjectNode updated = ((ObjectNode) account).deepCopy();
        updated.remove("password");
        updated.put("passwordHash", passwordEncoder.encode(newPassword));
        updated.put("currentPassword", newPassword);
        updated.put("updatedAt", Instant.now().toString());
        jdbcTemplate.update("UPDATE clinic_accounts SET raw_json = ? WHERE id = ?", toJson(updated), text(updated, "id"));
    }

    public LoginOptions loginOptions() {
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
        String username = text(account, "username", text(account, "id"));
        return Map.of(
            "id", text(account, "id", username),
            "username", username,
            "name", text(account, "name", username),
            "department", text(account, "department")
        );
    }

    private Optional<JsonNode> loadAccount(String username) {
        return jdbcTemplate.query(
            "SELECT id, raw_json FROM clinic_accounts WHERE LOWER(username) = ? OR id = ? LIMIT 1",
            resultSet -> {
                if (!resultSet.next()) return Optional.empty();
                ObjectNode account = readJson(resultSet.getString("raw_json"));
                if (!account.hasNonNull("id")) account.put("id", resultSet.getString("id"));
                return Optional.of(account);
            },
            username,
            username
        );
    }

    private List<ObjectNode> loadEnabledAccounts() {
        return jdbcTemplate.query(
            "SELECT raw_json FROM clinic_accounts ORDER BY username",
            (resultSet, rowNum) -> readJson(resultSet.getString("raw_json"))
        ).stream()
            .filter(account -> "启用".equals(text(account, "status", "启用")))
            .sorted(Comparator
                .comparing((ObjectNode account) -> text(account, "department"))
                .thenComparing(account -> text(account, "name", text(account, "username")))
                .thenComparing(account -> text(account, "username", text(account, "id"))))
            .toList();
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

    private void cleanupExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        loginFailures.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private void rejectIfLocked(String failureKey) {
        LoginFailure failure = loginFailures.get(failureKey);
        if (failure != null && failure.isLocked(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请稍后再试");
        }
    }

    private void registerFailedLogin(String failureKey) {
        Instant now = Instant.now();
        loginFailures.compute(failureKey, (key, existing) -> {
            int attempts = existing == null || existing.isExpired(now) ? 1 : existing.attempts() + 1;
            Instant lockedUntil = attempts >= MAX_FAILED_ATTEMPTS ? now.plus(LOGIN_LOCK_TTL) : null;
            return new LoginFailure(attempts, lockedUntil, now);
        });
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
        boolean isLocked(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        boolean isExpired(Instant now) {
            return lockedUntil != null && lockedUntil.isBefore(now)
                || lockedUntil == null && lastFailedAt.plus(LOGIN_LOCK_TTL).isBefore(now);
        }
    }

}
