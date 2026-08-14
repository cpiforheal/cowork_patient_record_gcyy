package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-enforced inventory roles. Clinical roles and inventory roles are deliberately separate. */
@Service
@Profile("mysql")
public class InventoryAccessService {

    public static final String ADMIN = "inventory_admin";
    private static final String WAREHOUSE = "inventory_warehouse";
    private static final String DEPARTMENT = "inventory_department";
    public static final String DEPARTMENT_REPORTER = "inventory_department_reporter";
    private static final String AUDITOR = "inventory_auditor";
    private static final String VIEWER = "inventory_viewer";
    private static final String NO_ACCESS = "";

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Profile> profiles = profiles();

    public InventoryAccessService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Access accessFor(SessionUser user) {
        if (user == null) return Access.none();
        if ("admin".equals(RoleCatalog.canonicalize(user.role()))) return Access.of(profiles.get(ADMIN));
        String roleCode = jdbcTemplate.query(
            "SELECT role_code FROM inventory_account_roles WHERE account_id = ?",
            resultSet -> resultSet.next() ? resultSet.getString(1) : "",
            user.id()
        );
        Profile profile = profiles.get(normalize(roleCode));
        return profile == null ? Access.none() : Access.of(profile);
    }

    public boolean hasCapability(SessionUser user, String capability) {
        return accessFor(user).capabilities().contains(capability);
    }

    public boolean canViewAllDepartments(SessionUser user) {
        return accessFor(user).allDepartments();
    }

    public List<Map<String, Object>> roleCatalog() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT role_code, COUNT(*) member_count FROM inventory_account_roles GROUP BY role_code", resultSet -> {
            while (resultSet.next()) counts.put(normalize(resultSet.getString("role_code")), resultSet.getLong("member_count"));
            return null;
        });
        return profiles.values().stream().map(profile -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", profile.code());
            row.put("name", profile.name());
            row.put("responsibility", profile.responsibility());
            row.put("dataScope", profile.allDepartments() ? "全院进销存数据" : "仅本人所属科室");
            row.put("permissions", profile.permissionLabels());
            row.put("memberCount", counts.getOrDefault(profile.code(), 0L));
            row.put("systemAssigned", ADMIN.equals(profile.code()));
            return row;
        }).toList();
    }

    public List<Map<String, Object>> accountAssignments() {
        return jdbcTemplate.query(
            """
            SELECT a.id, a.username,
                   MAX(COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.name')), ''), a.username)) name,
                   a.role, a.status, r.role_code,
                   GROUP_CONCAT(DISTINCT d.name ORDER BY d.name SEPARATOR '、') departments
            FROM clinic_accounts a
            LEFT JOIN inventory_account_roles r ON r.account_id = a.id
            LEFT JOIN clinic_account_departments ad ON ad.account_id = a.id AND ad.status = 'ACTIVE'
            LEFT JOIN clinic_departments d ON d.id = ad.department_id AND d.status = 'ACTIVE'
            GROUP BY a.id, a.username, a.role, a.status, r.role_code
            ORDER BY a.status DESC, name, a.username
            """,
            (resultSet, ignored) -> accountRow(resultSet)
        );
    }

    @Transactional
    public void assign(String accountId, String roleCode, SessionUser operator) {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("请选择需要配置的账号");
        String requestedRole = normalize(roleCode);
        String clinicalRole = jdbcTemplate.query(
            "SELECT role FROM clinic_accounts WHERE id = ?",
            resultSet -> resultSet.next() ? resultSet.getString(1) : null,
            accountId
        );
        if (clinicalRole == null) throw new IllegalArgumentException("账号不存在或已被删除");
        if ("admin".equals(RoleCatalog.canonicalize(clinicalRole))) {
            jdbcTemplate.update("DELETE FROM inventory_account_roles WHERE account_id = ?", accountId);
            return;
        }
        if (requestedRole.isBlank()) {
            jdbcTemplate.update("DELETE FROM inventory_account_roles WHERE account_id = ?", accountId);
            return;
        }
        if (ADMIN.equals(requestedRole) || !profiles.containsKey(requestedRole)) {
            throw new IllegalArgumentException("该进销存角色不可分配");
        }
        jdbcTemplate.update(
            """
            INSERT INTO inventory_account_roles (account_id, role_code, assigned_by)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE role_code = VALUES(role_code), assigned_by = VALUES(assigned_by)
            """,
            accountId, requestedRole, operator == null ? "" : operator.id()
        );
    }

    private Map<String, Object> accountRow(ResultSet resultSet) throws SQLException {
        String clinicalRole = RoleCatalog.canonicalize(resultSet.getString("role"));
        String assignedRole = "admin".equals(clinicalRole) ? ADMIN : normalize(resultSet.getString("role_code"));
        Profile profile = profiles.get(assignedRole);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", resultSet.getString("id"));
        row.put("username", resultSet.getString("username"));
        row.put("name", resultSet.getString("name"));
        row.put("clinicalRole", RoleCatalog.label(clinicalRole));
        row.put("department", value(resultSet.getString("departments")));
        row.put("status", "ACTIVE".equalsIgnoreCase(resultSet.getString("status")) ? "启用" : "停用");
        row.put("inventoryRole", assignedRole);
        row.put("inventoryRoleLabel", profile == null ? "未开通" : profile.name());
        row.put("systemAssigned", "admin".equals(clinicalRole));
        return row;
    }

    private static Map<String, Profile> profiles() {
        Map<String, Profile> result = new LinkedHashMap<>();
        result.put(ADMIN, profile(
            ADMIN, "进销存管理员", "维护角色分配、物资档案、库存、规则与全院报表。", true,
            paths("/inventory/overview", "/inventory/executive", "/inventory/requests", "/inventory/stock", "/inventory/items", "/inventory/controls", "/inventory/packages", "/inventory/weekly", "/inventory/trace", "/inventory/daily", "/inventory/roles"),
            permissions("inventoryOverview=inventory:read,inventory:approve,inventory:issue,inventory:receive,inventory:count,inventory:export", "inventoryExecutive=inventory:read,inventory:export", "inventoryRequests=inventory:read,inventory:approve,inventory:issue,inventory:receive,inventory:export", "inventoryStock=inventory:read,inventory:issue,inventory:receive,inventory:count,inventory:export", "inventoryItems=inventory:read,inventory:item:manage,inventory:export", "inventoryControls=inventory:read,inventory:receive,inventory:count,inventory:export", "inventoryPackages=inventory:read,inventory:rule,inventory:retry", "inventoryWeekly=inventory:read,inventory:rule,inventory:confirm,inventory:export", "inventoryTrace=inventory:read,inventory:export", "inventoryDaily=inventory:read,inventory:export", "inventoryRoles=inventory:read,inventory:role:manage")
        ));
        result.put(WAREHOUSE, profile(
            WAREHOUSE, "库房管理员", "负责物资档案、入库、审批发放、盘点报损和出入库追溯。", true,
            paths("/inventory/overview", "/inventory/requests", "/inventory/stock", "/inventory/items", "/inventory/controls", "/inventory/weekly", "/inventory/trace"),
            permissions("inventoryOverview=inventory:read,inventory:approve,inventory:issue,inventory:receive,inventory:count,inventory:export", "inventoryRequests=inventory:read,inventory:approve,inventory:issue,inventory:receive,inventory:export", "inventoryStock=inventory:read,inventory:issue,inventory:receive,inventory:count,inventory:export", "inventoryItems=inventory:read,inventory:item:manage,inventory:export", "inventoryControls=inventory:read,inventory:receive,inventory:count,inventory:export", "inventoryWeekly=inventory:read,inventory:export", "inventoryTrace=inventory:read,inventory:export")
        ));
        result.put(DEPARTMENT, profile(
            DEPARTMENT, "科室领用员", "查看本科室物资，提交申领并签收；不可维护库存或扣减规则。", false,
            paths("/inventory/overview", "/inventory/requests", "/inventory/weekly"),
            permissions("inventoryOverview=inventory:read,inventory:request,inventory:receive", "inventoryRequests=inventory:read,inventory:request,inventory:receive", "inventoryWeekly=inventory:read")
        ));
        result.put(DEPARTMENT_REPORTER, profile(
            DEPARTMENT_REPORTER, "科室耗材填报员", "仅填写本部门耗材日报。", false,
            paths("/inventory/daily"),
            permissions("inventoryDaily=inventory:read")
        ));
        result.put(AUDITOR, profile(
            AUDITOR, "耗材质控员", "维护患者变量规则，复核日核表并导出全院耗材报表。", true,
            paths("/inventory/overview", "/inventory/executive", "/inventory/packages", "/inventory/weekly", "/inventory/trace", "/inventory/daily"),
            permissions("inventoryOverview=inventory:read,inventory:export", "inventoryExecutive=inventory:read,inventory:export", "inventoryPackages=inventory:read,inventory:rule,inventory:retry", "inventoryWeekly=inventory:read,inventory:rule,inventory:confirm,inventory:export", "inventoryTrace=inventory:read,inventory:export", "inventoryDaily=inventory:read,inventory:export")
        ));
        result.put(VIEWER, profile(
            VIEWER, "管理查看者", "查看全院库存与耗材报表，仅可导出，不可修改任何业务数据。", true,
            paths("/inventory/overview", "/inventory/executive", "/inventory/trace", "/inventory/daily"),
            permissions("inventoryOverview=inventory:read,inventory:export", "inventoryExecutive=inventory:read,inventory:export", "inventoryTrace=inventory:read,inventory:export", "inventoryDaily=inventory:read,inventory:export")
        ));
        return Map.copyOf(result);
    }

    private static Profile profile(String code, String name, String responsibility, boolean allDepartments, Set<String> paths, Map<String, List<String>> buttons) {
        Set<String> capabilities = new LinkedHashSet<>();
        buttons.values().forEach(capabilities::addAll);
        return new Profile(code, name, responsibility, allDepartments, paths, buttons, Set.copyOf(capabilities), permissionLabels(capabilities));
    }

    private static List<String> permissionLabels(Set<String> capabilities) {
        Map<String, String> labels = Map.ofEntries(
            Map.entry("inventory:read", "查看耗材与库存"), Map.entry("inventory:request", "提交申领"), Map.entry("inventory:receive", "确认签收"), Map.entry("inventory:approve", "审批申领"), Map.entry("inventory:issue", "入库、发放与扣减"), Map.entry("inventory:count", "盘点与报损"), Map.entry("inventory:item:manage", "维护物资档案"), Map.entry("inventory:rule", "维护扣减规则"), Map.entry("inventory:confirm", "确认周核表"), Map.entry("inventory:retry", "处理异常任务"), Map.entry("inventory:export", "导出报表"), Map.entry("inventory:role:manage", "配置岗位权限")
        );
        return capabilities.stream().map(capability -> labels.getOrDefault(capability, capability)).sorted().toList();
    }

    private static Set<String> paths(String... values) {
        return Set.of(values);
    }

    private static Map<String, List<String>> permissions(String... definitions) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String definition : definitions) {
            String[] parts = definition.split("=", 2);
            result.put(parts[0], List.of(parts[1].split(",")));
        }
        return Map.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String value(String source) {
        return source == null ? "" : source;
    }

    public record Access(Profile profile) {
        static Access none() { return new Access(null); }
        static Access of(Profile profile) { return new Access(profile); }
        public Set<String> menuPaths() { return profile == null ? Set.of() : profile.menuPaths(); }
        public Map<String, List<String>> buttonPermissions() { return profile == null ? Map.of() : profile.buttonPermissions(); }
        public Set<String> capabilities() { return profile == null ? Set.of() : profile.capabilities(); }
        public boolean allDepartments() { return profile != null && profile.allDepartments(); }
    }

    public record Profile(String code, String name, String responsibility, boolean allDepartments, Set<String> menuPaths,
                          Map<String, List<String>> buttonPermissions, Set<String> capabilities, List<String> permissionLabels) {}
}
