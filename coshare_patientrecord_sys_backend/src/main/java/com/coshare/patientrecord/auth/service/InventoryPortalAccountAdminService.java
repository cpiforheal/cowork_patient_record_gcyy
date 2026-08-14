package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Administrative maintenance for accounts dedicated to the standalone inventory portal. */
@Service
@Profile("mysql")
public class InventoryPortalAccountAdminService {
    private static final String ADMIN = "admin";
    private static final String REPORTER = "inventory_reporter";
    private static final String REPORTER_ROLE = InventoryAccessService.DEPARTMENT_REPORTER;
    private static final Map<String, String> DEPARTMENTS = departments();

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public InventoryPortalAccountAdminService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> accounts() {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT id, username, display_name, department_key, department_name, clinic_role, must_change_password, status, display_order "
                + "FROM inventory_portal_accounts ORDER BY CASE WHEN clinic_role = 'admin' THEN 0 ELSE 1 END, display_order, username",
            (resultSet, ignored) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                String role = resultSet.getString("clinic_role");
                row.put("id", resultSet.getString("id"));
                row.put("username", resultSet.getString("username"));
                row.put("name", resultSet.getString("display_name"));
                row.put("departmentKey", resultSet.getString("department_key"));
                row.put("department", resultSet.getString("department_name"));
                row.put("portalRole", role);
                row.put("portalRoleLabel", ADMIN.equals(role) ? "进销存管理员" : "科室填报员");
                row.put("status", resultSet.getString("status"));
                row.put("mustChangePassword", resultSet.getBoolean("must_change_password"));
                row.put("displayOrder", resultSet.getInt("display_order"));
                return row;
            }
        );
        List<Map<String, String>> departments = DEPARTMENTS.entrySet().stream()
            .map(entry -> Map.of("key", entry.getKey(), "name", entry.getValue()))
            .toList();
        return Map.of("accounts", rows, "departments", departments);
    }

    @Transactional
    public void update(String accountId, JsonNode payload, SessionUser operator) {
        Account account = requireAccount(accountId);
        String role = text(payload, "portalRole", account.portalRole());
        if (!ADMIN.equals(role) && !REPORTER.equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的进销存岗位");
        }
        String status = text(payload, "status", account.status());
        if (!"启用".equals(status) && !"停用".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号状态仅支持启用或停用");
        }
        String departmentKey = text(payload, "departmentKey", account.departmentKey());
        String departmentName;
        if (ADMIN.equals(role)) {
            departmentKey = "inventory-admin";
            departmentName = "管理端";
        } else {
            departmentName = DEPARTMENTS.get(departmentKey);
            if (departmentName == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的科室");
        }
        jdbcTemplate.update(
            "UPDATE inventory_portal_accounts SET department_key = ?, department_name = ?, clinic_role = ?, status = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            departmentKey, departmentName, role, status, account.id()
        );
        if (ADMIN.equals(role)) {
            jdbcTemplate.update("DELETE FROM inventory_account_roles WHERE account_id = ?", account.id());
        } else {
            jdbcTemplate.update(
                "INSERT INTO inventory_account_roles (account_id, role_code, assigned_by) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE role_code = VALUES(role_code), assigned_by = VALUES(assigned_by), updated_at = CURRENT_TIMESTAMP",
                account.id(), REPORTER_ROLE, operator == null ? "" : operator.id()
            );
        }
        revokeSessions(account.id());
    }

    @Transactional
    public void resetPassword(String accountId) {
        Account account = requireAccount(accountId);
        jdbcTemplate.update(
            "UPDATE inventory_portal_accounts SET password_hash = ?, must_change_password = TRUE, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
            passwordEncoder.encode("123456"), account.id()
        );
        revokeSessions(account.id());
    }

    private Account requireAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择进销存账号");
        Account account = jdbcTemplate.query(
            "SELECT id, department_key, clinic_role, status FROM inventory_portal_accounts WHERE id = ? LIMIT 1",
            resultSet -> resultSet.next() ? new Account(
                resultSet.getString("id"), resultSet.getString("department_key"), resultSet.getString("clinic_role"), resultSet.getString("status")
            ) : null,
            accountId
        );
        if (account == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "进销存账号不存在");
        return account;
    }

    private void revokeSessions(String accountId) {
        jdbcTemplate.update("UPDATE inventory_portal_sessions SET revoked_at = CURRENT_TIMESTAMP(6) WHERE user_id = ? AND revoked_at IS NULL", accountId);
    }

    private static String text(JsonNode payload, String field, String fallback) {
        if (payload == null || !payload.hasNonNull(field)) return fallback;
        String value = payload.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private static Map<String, String> departments() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("physiotherapy", "理疗室");
        result.put("tcm", "中医科");
        result.put("tcm-pharmacy", "中药房");
        result.put("logistics", "后勤");
        result.put("western-pharmacy", "西药房");
        result.put("operating", "手术室");
        result.put("nursing", "护理部");
        result.put("cashier", "收费室");
        result.put("inspection", "检查室");
        result.put("laboratory", "检验科");
        result.put("endoscopy", "胃肠镜");
        result.put("anesthesia", "麻醉室");
        return Map.copyOf(result);
    }

    private record Account(String id, String departmentKey, String portalRole, String status) {}
}
