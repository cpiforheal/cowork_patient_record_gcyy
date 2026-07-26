package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.AccountSummary;
import com.coshare.patientrecord.auth.dto.AccountUpsertRequest;
import com.coshare.patientrecord.auth.dto.DirectoryAccountOption;
import com.coshare.patientrecord.auth.dto.PasswordResetRequest;
import com.coshare.patientrecord.auth.dto.RoleDescriptor;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class AuthAccountAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;

    public AuthAccountAdminService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        PasswordEncoder passwordEncoder,
        AuthSessionService authSessionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
    }

    public List<RoleDescriptor> roles() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT role, COUNT(*) AS member_count FROM clinic_accounts GROUP BY role", rs -> {
            counts.merge(RoleCatalog.canonicalize(rs.getString("role")), rs.getLong("member_count"), Long::sum);
        });
        return RoleCatalog.definitions().stream()
            .map(role -> new RoleDescriptor(
                role.role(),
                role.name(),
                role.responsibility(),
                role.entries(),
                role.actions(),
                role.dataScope(),
                counts.getOrDefault(role.role(), 0L)
            ))
            .toList();
    }

    public List<AccountSummary> accounts() {
        return jdbcTemplate.query(
            "SELECT id, username, role, status, raw_json FROM clinic_accounts ORDER BY username, id",
            (rs, rowNum) -> toSummary(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("role"),
                rs.getString("status"),
                readJson(rs.getString("raw_json"))
            )
        );
    }

    public List<DirectoryAccountOption> directoryAccounts(SessionUser user, String requestedDepartmentId) {
        String departmentId = normalize(requestedDepartmentId);
        if (departmentId.isBlank()) departmentId = user.activeDepartmentId();
        if (!departmentId.equals(user.activeDepartmentId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能查询当前活动科室的岗位人员");
        }
        return jdbcTemplate.query(
            """
            SELECT a.id, a.username, a.role, a.raw_json, d.name AS department
            FROM clinic_accounts a
            JOIN clinic_account_departments ad
              ON ad.account_id = a.id AND ad.department_id = ? AND ad.status = 'ACTIVE'
            JOIN clinic_departments d ON d.id = ad.department_id AND d.status = 'ACTIVE'
            WHERE a.status = '启用'
            ORDER BY a.role, a.username, a.id
            """,
            (rs, rowNum) -> {
                ObjectNode raw = readJson(rs.getString("raw_json"));
                String role = RoleCatalog.canonicalize(rs.getString("role"));
                return new DirectoryAccountOption(
                    rs.getString("id"),
                    text(raw, "name", rs.getString("username")),
                    rs.getString("username"),
                    role,
                    RoleCatalog.label(role),
                    rs.getString("department")
                );
            },
            departmentId
        ).stream().filter(option -> RoleCatalog.isCanonical(option.role())).toList();
    }

    @Transactional
    public AccountSummary create(AccountUpsertRequest request) {
        return save(UUID.randomUUID().toString(), request, true);
    }

    @Transactional
    public AccountSummary update(String accountId, AccountUpsertRequest request, String operatorId) {
        requireExisting(accountId);
        ensureAdministratorContinuity(accountId, request, operatorId);
        return save(accountId, request, false);
    }

    @Transactional
    public void resetPassword(String accountId, PasswordResetRequest request) {
        ObjectNode account = requireExisting(accountId);
        String password = request == null ? "" : normalize(request.newPassword());
        validatePassword(password);
        account.put("passwordHash", passwordEncoder.encode(password));
        account.put("mustChangePassword", true);
        account.put("updatedAt", Instant.now().toString());
        jdbcTemplate.update("UPDATE clinic_accounts SET raw_json = CAST(? AS JSON) WHERE id = ?", toJson(account), accountId);
        authSessionService.revokeAllSessions(accountId, "password_reset");
    }

    @Transactional
    public void delete(String accountId, String operatorId) {
        if (normalize(accountId).isBlank()) throw badRequest("账号标识不能为空");
        if (accountId.equals(operatorId)) throw badRequest("不能删除当前登录账号");
        ObjectNode existing = requireExisting(accountId);
        if ("admin".equals(RoleCatalog.canonicalize(text(existing, "role")))
            && "启用".equals(text(existing, "status", "启用"))) {
            requireAnotherEnabledAdministrator(accountId);
        }
        authSessionService.revokeAllSessions(accountId, "account_deleted");
        jdbcTemplate.update("DELETE FROM clinic_accounts WHERE id = ?", accountId);
    }

    private void ensureAdministratorContinuity(String accountId, AccountUpsertRequest request, String operatorId) {
        ObjectNode existing = requireExisting(accountId);
        boolean enabledAdmin = "admin".equals(RoleCatalog.canonicalize(text(existing, "role")))
            && "启用".equals(text(existing, "status", "启用"));
        if (!enabledAdmin) return;
        String nextRole = request == null ? "" : normalize(request.role()).toLowerCase(java.util.Locale.ROOT);
        String nextStatus = request == null ? "" : normalizeStatus(request.status());
        boolean removesAdministration = !"admin".equals(nextRole) || !"启用".equals(nextStatus);
        if (!removesAdministration) return;
        if (accountId.equals(operatorId)) throw badRequest("不能停用当前管理员或将当前管理员改为其他岗位");
        requireAnotherEnabledAdministrator(accountId);
    }

    private void requireAnotherEnabledAdministrator(String excludedAccountId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_accounts WHERE role = 'admin' AND status = '启用' AND id <> ?",
            Integer.class,
            excludedAccountId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "系统必须至少保留一个启用的管理员账号");
        }
    }

    private AccountSummary save(String accountId, AccountUpsertRequest request, boolean creating) {
        if (request == null) throw badRequest("账号信息不能为空");
        String username = normalize(request.username());
        String name = normalize(request.name());
        String role = normalize(request.role()).toLowerCase(java.util.Locale.ROOT);
        String status = normalizeStatus(request.status());
        String password = normalize(request.password());
        String primaryDepartmentId = normalize(request.primaryDepartmentId());
        LinkedHashSet<String> departmentIds = normalizedDepartments(request.departmentIds());

        if (username.length() < 3 || username.length() > 100) throw badRequest("账号名长度必须为 3-100 个字符");
        if (name.isBlank() || name.length() > 100) throw badRequest("姓名不能为空且不能超过 100 个字符");
        if (!RoleCatalog.isCanonical(role)) throw badRequest("请选择系统提供的规范岗位");
        if (departmentIds.isEmpty()) throw badRequest("账号必须授权至少一个科室");
        if (!departmentIds.contains(primaryDepartmentId)) throw badRequest("主科室必须包含在授权科室中");
        validateDepartments(departmentIds);
        ensureUniqueUsername(username, accountId);
        if (creating || !password.isBlank()) validatePassword(password);

        ObjectNode previous = creating ? objectMapper.createObjectNode() : requireExisting(accountId);
        ObjectNode account = previous.deepCopy();
        account.put("id", accountId);
        account.put("username", username);
        account.put("name", name);
        account.put("role", role);
        account.put("roleLabel", RoleCatalog.label(role));
        account.put("status", status);
        account.put("scope", normalize(request.scope()));
        account.put("primaryDepartmentId", primaryDepartmentId);
        account.set("departmentIds", toArray(departmentIds));
        account.put("department", departmentName(primaryDepartmentId));
        account.put("updatedAt", Instant.now().toString());
        if (creating) account.put("createdAt", Instant.now().toString());
        if (!password.isBlank()) {
            account.put("passwordHash", passwordEncoder.encode(password));
            account.put("mustChangePassword", true);
        }
        account.remove(List.of("password", "currentPassword"));

        jdbcTemplate.update(
            """
            INSERT INTO clinic_accounts (id, username, role, status, raw_json)
            VALUES (?, ?, ?, ?, CAST(? AS JSON))
            ON DUPLICATE KEY UPDATE username = VALUES(username), role = VALUES(role),
              status = VALUES(status), raw_json = VALUES(raw_json)
            """,
            accountId, username, role, status, toJson(account)
        );
        replaceDepartments(accountId, departmentIds, primaryDepartmentId);
        if (!creating && identityChanged(previous, account)) {
            authSessionService.revokeAllSessions(accountId, "account_identity_changed");
        }
        return toSummary(accountId, username, role, status, account);
    }

    private AccountSummary toSummary(String id, String username, String roleValue, String status, ObjectNode raw) {
        String role = RoleCatalog.canonicalize(roleValue);
        List<DepartmentMembership> memberships = jdbcTemplate.query(
            """
            SELECT d.id, d.name, ad.is_primary
            FROM clinic_account_departments ad
            JOIN clinic_departments d ON d.id = ad.department_id
            WHERE ad.account_id = ? AND ad.status = 'ACTIVE' AND d.status = 'ACTIVE'
            ORDER BY ad.is_primary DESC, d.name, d.id
            """,
            (rs, rowNum) -> new DepartmentMembership(rs.getString("id"), rs.getString("name"), rs.getBoolean("is_primary")),
            id
        );
        String primaryId = memberships.stream().filter(DepartmentMembership::primary).map(DepartmentMembership::id).findFirst().orElse("");
        String department = memberships.stream().filter(DepartmentMembership::primary).map(DepartmentMembership::name).findFirst().orElse("");
        return new AccountSummary(
            id,
            username,
            text(raw, "name", username),
            role,
            RoleCatalog.label(role),
            status,
            memberships.stream().map(DepartmentMembership::id).toList(),
            primaryId,
            department,
            text(raw, "scope")
        );
    }

    private ObjectNode requireExisting(String accountId) {
        List<ObjectNode> values = jdbcTemplate.query(
            "SELECT username, role, status, raw_json FROM clinic_accounts WHERE id = ? LIMIT 1",
            (rs, rowNum) -> {
                ObjectNode account = readJson(rs.getString("raw_json"));
                account.put("username", rs.getString("username"));
                account.put("role", rs.getString("role"));
                account.put("status", rs.getString("status"));
                return account;
            },
            accountId
        );
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        return values.get(0);
    }

    private void ensureUniqueUsername(String username, String accountId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_accounts WHERE LOWER(username) = LOWER(?) AND id <> ?",
            Integer.class,
            username,
            accountId
        );
        if (count != null && count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "账号名已存在");
    }

    private void validateDepartments(Set<String> departmentIds) {
        for (String departmentId : departmentIds) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clinic_departments WHERE id = ? AND status = 'ACTIVE'",
                Integer.class,
                departmentId
            );
            if (count == null || count != 1) throw badRequest("授权科室不存在或已停用: " + departmentId);
        }
    }

    private String departmentName(String departmentId) {
        return jdbcTemplate.queryForObject("SELECT name FROM clinic_departments WHERE id = ?", String.class, departmentId);
    }

    private void replaceDepartments(String accountId, Set<String> departmentIds, String primaryDepartmentId) {
        jdbcTemplate.update("DELETE FROM clinic_account_departments WHERE account_id = ?", accountId);
        for (String departmentId : departmentIds) {
            jdbcTemplate.update(
                """
                INSERT INTO clinic_account_departments (account_id, department_id, is_primary, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """,
                accountId,
                departmentId,
                departmentId.equals(primaryDepartmentId)
            );
        }
    }

    private boolean identityChanged(ObjectNode before, ObjectNode after) {
        return !text(before, "username").equals(text(after, "username"))
            || !RoleCatalog.canonicalize(text(before, "role")).equals(text(after, "role"))
            || !text(before, "status").equals(text(after, "status"))
            || !text(before, "primaryDepartmentId").equals(text(after, "primaryDepartmentId"))
            || !before.path("departmentIds").equals(after.path("departmentIds"))
            || !text(before, "passwordHash").equals(text(after, "passwordHash"));
    }

    private LinkedHashSet<String> normalizedDepartments(List<String> source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (source == null) return result;
        source.stream().map(this::normalize).filter(value -> !value.isBlank()).forEach(result::add);
        return result;
    }

    private ArrayNode toArray(Set<String> values) {
        ArrayNode result = objectMapper.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private String normalizeStatus(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank() || "启用".equals(normalized) || "ACTIVE".equalsIgnoreCase(normalized)) return "启用";
        if ("停用".equals(normalized) || "INACTIVE".equalsIgnoreCase(normalized)) return "停用";
        throw badRequest("账号状态只能为启用或停用");
    }

    private void validatePassword(String password) {
        if (password.length() < 8 || password.length() > 128) throw badRequest("密码长度必须为 8-128 个字符");
    }

    private ObjectNode readJson(String rawJson) {
        try {
            JsonNode value = objectMapper.readTree(rawJson);
            return value != null && value.isObject() ? (ObjectNode) value : objectMapper.createObjectNode();
        } catch (Exception error) {
            throw new IllegalStateException("账号数据损坏", error);
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("账号数据序列化失败", error);
        }
    }

    private String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record DepartmentMembership(String id, String name, boolean primary) {}
}
