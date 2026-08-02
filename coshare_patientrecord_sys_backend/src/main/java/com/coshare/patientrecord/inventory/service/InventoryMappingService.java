package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class InventoryMappingService {

    static final String PATIENT_ONCE_PACKAGE = "\u60a3\u8005\u5355\u6b21\u5957\u9910";
    static final String PENDING_STAGE = "\u5f85\u786e\u8ba4";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryPackageService packageService;

    public InventoryMappingService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryPackageService packageService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.packageService = packageService;
    }

    public ObjectNode summary(SessionUser user) {
        QuerySpec scope = buildFilter(user, "", "", "", "");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("total", count("SELECT COUNT(*) FROM inventory_mapping_entries e " + scope.where(), scope.params()));

        ArrayNode byRuleType = objectMapper.createArrayNode();
        queryCounts(
            """
            SELECT e.rule_type label, COUNT(*) total
            FROM inventory_mapping_entries e
            """,
            scope,
            "e.rule_type",
            byRuleType
        );
        result.set("byRuleType", byRuleType);

        ArrayNode byStatus = objectMapper.createArrayNode();
        queryCounts(
            """
            SELECT e.status label, COUNT(*) total
            FROM inventory_mapping_entries e
            """,
            scope,
            "e.status",
            byStatus
        );
        result.set("byStatus", byStatus);
        result.put("batchId", "inventory-mapping-batch-20260802");
        return result;
    }

    public ObjectNode entries(
        SessionUser user,
        String ruleType,
        String status,
        String department,
        String keyword,
        int page,
        int size
    ) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        int offset = (safePage - 1) * safeSize;
        QuerySpec filter = buildFilter(user, ruleType, status, department, keyword);
        List<Object> pageParams = new ArrayList<>(filter.params());
        pageParams.add(safeSize);
        pageParams.add(offset);

        String from = """
            FROM inventory_mapping_entries e
            LEFT JOIN inventory_items i ON i.id = e.matched_item_id
            """;
        long total = count("SELECT COUNT(*) " + from + filter.where(), filter.params());

        ArrayNode list = objectMapper.createArrayNode();
        jdbcTemplate.query(
            mappingSelect() + from + filter.where() + """
            ORDER BY
              FIELD(e.rule_type, '\u60a3\u8005\u5355\u6b21\u5957\u9910', '\u6761\u4ef6\u5957\u9910', '\u56fa\u5b9a\u8fd0\u884c\u6d88\u8017', '\u6309\u9700\u7533\u9886'),
              e.source_sheet ASC, e.source_row ASC, e.id ASC
            LIMIT ? OFFSET ?
            """,
            resultSet -> {
                while (resultSet.next()) list.add(decorate(row(resultSet)));
                return null;
            },
            pageParams.toArray()
        );

        ObjectNode result = objectMapper.createObjectNode();
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.set("list", list);
        return result;
    }

    @Transactional
    public ObjectNode confirm(JsonNode payload, SessionUser user) {
        List<String> ids = ids(payload);
        ArrayNode updated = objectMapper.createArrayNode();
        for (String id : ids) {
            ObjectNode row = loadRow(id, true);
            if (row == null) throw new IllegalArgumentException("Mapping entry does not exist: " + id);

            String department = text(payload, "department", text(row, "department"));
            String departmentId = text(payload, "departmentId", text(row, "departmentId"));
            Department resolvedDepartment = resolveDepartment(departmentId, department);
            if (!resolvedDepartment.id().isBlank()) departmentId = resolvedDepartment.id();
            if (!resolvedDepartment.name().isBlank()) department = resolvedDepartment.name();

            String matchedItemId = text(payload, "itemId", text(payload, "matchedItemId", text(row, "matchedItemId")));
            String matchedItemName = text(payload, "itemName", text(row, "matchedItemName"));
            if (!matchedItemId.isBlank()) {
                ObjectNode item = item(matchedItemId);
                if (item == null) throw new IllegalArgumentException("Matched inventory item does not exist: " + matchedItemId);
                matchedItemName = text(item, "name", matchedItemName);
            }

            String careType = normalizeCareType(text(payload, "careType", text(row, "careType")));
            String triggerStage = normalizeTriggerStage(text(payload, "triggerStage", text(row, "triggerStage")));
            String suggestedUnit = text(payload, "suggestedUnit", text(row, "suggestedUnit"));
            BigDecimal suggestedQuantity = payload.has("suggestedQuantity")
                ? quantity(payload, "suggestedQuantity")
                : quantity(row, "suggestedQuantity");
            String operator = user == null ? "" : user.name();
            String confirmedAt = now();

            row.put("department", department);
            row.put("departmentId", departmentId);
            row.put("careType", careType);
            row.put("triggerStage", triggerStage);
            row.put("suggestedUnit", suggestedUnit);
            putQuantity(row, "suggestedQuantity", suggestedQuantity);
            row.put("matchedItemId", matchedItemId);
            row.put("matchedItemName", matchedItemName);
            row.put("status", "confirmed");
            row.put("operator", operator);
            row.put("confirmedAt", confirmedAt);
            String reason = cannotCreatePackageDraftReason(
                row,
                itemEnabled(row),
                matchedItemUnit(row),
                conversionExists(matchedItemId, suggestedUnit, matchedItemUnit(row))
            );
            row.put("cannotPublishReason", reason);

            jdbcTemplate.update(
                """
                UPDATE inventory_mapping_entries
                SET department = ?, department_id = ?, care_type = ?, trigger_stage = ?,
                    suggested_quantity = ?, suggested_unit = ?, matched_item_id = ?, matched_item_name = ?,
                    status = 'confirmed', operator_name = ?, confirmed_at = ?, cannot_publish_reason = ?,
                    raw_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                department, emptyToNull(departmentId), careType, triggerStage, suggestedQuantity, suggestedUnit,
                emptyToNull(matchedItemId), emptyToNull(matchedItemName), operator, confirmedAt, reason, toJson(row), id
            );
            updated.add(decorate(loadRow(id, false)));
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("updated", updated.size());
        result.set("list", updated);
        return result;
    }

    @Transactional
    public ObjectNode hold(JsonNode payload, SessionUser user) {
        List<String> ids = ids(payload);
        String reason = text(payload, "reason", "Mapping held for later confirmation.");
        ArrayNode updated = objectMapper.createArrayNode();
        for (String id : ids) {
            ObjectNode row = loadRow(id, true);
            if (row == null) throw new IllegalArgumentException("Mapping entry does not exist: " + id);
            row.put("status", "held");
            row.put("cannotPublishReason", reason);
            row.put("operator", user == null ? "" : user.name());
            jdbcTemplate.update(
                """
                UPDATE inventory_mapping_entries
                SET status = 'held', cannot_publish_reason = ?, operator_name = ?, raw_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                reason, user == null ? "" : user.name(), toJson(row), id
            );
            updated.add(decorate(loadRow(id, false)));
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("updated", updated.size());
        result.set("list", updated);
        return result;
    }

    @Transactional
    public ObjectNode createPackageDraft(JsonNode payload, SessionUser user) {
        List<String> ids = ids(payload);
        List<ObjectNode> rows = new ArrayList<>();
        for (String id : ids) {
            ObjectNode row = loadRow(id, true);
            if (row == null) throw new IllegalArgumentException("Mapping entry does not exist: " + id);
            ObjectNode decorated = decorate(row);
            String reason = text(decorated, "cannotPublishReason");
            if (!reason.isBlank()) throw new IllegalArgumentException("Mapping entry cannot create package draft: " + id + " - " + reason);
            rows.add(decorated);
        }
        validateSingleDraftScope(rows);

        ObjectNode first = rows.get(0);
        String departmentId = text(first, "departmentId");
        String department = text(first, "department");
        Department resolvedDepartment = resolveDepartment(departmentId, department);
        if (!resolvedDepartment.id().isBlank()) departmentId = resolvedDepartment.id();
        if (!resolvedDepartment.name().isBlank()) department = resolvedDepartment.name();

        ObjectNode draft = objectMapper.createObjectNode();
        draft.put("name", text(payload, "name", department + " patient consumables draft - " + text(first, "triggerStage")));
        draft.put("department", department);
        draft.put("departmentId", departmentId);
        draft.put("careType", text(first, "careType"));
        draft.put("triggerStage", text(first, "triggerStage"));
        draft.put("status", "draft");
        draft.put("source", "inventory_mapping_first_round");

        ArrayNode mappingIds = objectMapper.createArrayNode();
        LinkedHashMap<String, ObjectNode> lineByItem = new LinkedHashMap<>();
        for (ObjectNode row : rows) {
            mappingIds.add(text(row, "id"));
            String itemId = text(row, "matchedItemId");
            ObjectNode line = lineByItem.get(itemId);
            if (line == null) {
                line = objectMapper.createObjectNode();
                line.put("id", "pkg-line-" + UUID.randomUUID());
                line.put("itemId", itemId);
                line.put("itemName", text(row, "matchedItemName", text(row, "sourceItemName")));
                line.put("unit", text(row, "suggestedUnit"));
                line.put("consumptionMode", "per_visit");
                line.set("sourceMappingIds", objectMapper.createArrayNode());
                putQuantity(line, "quantity", BigDecimal.ZERO);
                lineByItem.put(itemId, line);
            }
            BigDecimal quantity = quantity(line, "quantity").add(quantity(row, "suggestedQuantity"));
            putQuantity(line, "quantity", quantity);
            line.withArray("sourceMappingIds").add(text(row, "id"));
        }
        ArrayNode lines = objectMapper.createArrayNode();
        lineByItem.values().forEach(lines::add);
        draft.set("lines", lines);
        draft.set("sourceMappingIds", mappingIds);

        ObjectNode saved = packageService.saveDraft(draft, user);
        String packageId = text(saved, "id");
        for (ObjectNode row : rows) {
            row.put("draftPackageId", packageId);
            jdbcTemplate.update(
                """
                UPDATE inventory_mapping_entries
                SET draft_package_id = ?, raw_json = ?, operator_name = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                packageId, toJson(row), user == null ? "" : user.name(), text(row, "id")
            );
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("created", true);
        result.put("draftPackageId", packageId);
        result.set("package", saved);
        result.put("mappingCount", rows.size());
        return result;
    }

    static String cannotCreatePackageDraftReason(
        JsonNode row,
        boolean matchedItemEnabled,
        String matchedItemUnit,
        boolean conversionExists
    ) {
        if (!text(row, "draftPackageId").isBlank()) return "Package draft already exists for this mapping.";
        if (!PATIENT_ONCE_PACKAGE.equals(text(row, "ruleType"))) return "Only patient-once mappings can create package drafts.";
        if (!"confirmed".equals(text(row, "status"))) return "Mapping is pending confirmation.";
        if (text(row, "department").isBlank()) return "Department is required.";
        if (!isSupportedCareType(text(row, "careType"))) return "Care type must be outpatient or inpatient.";
        String triggerStage = text(row, "triggerStage");
        if (triggerStage.isBlank() || PENDING_STAGE.equals(triggerStage)) return "Trigger stage needs confirmation.";
        if (text(row, "matchedItemId").isBlank()) return "Matched inventory item is required.";
        if (!matchedItemEnabled) return "Matched inventory item does not exist or is disabled.";
        if (quantity(row, "suggestedQuantity").signum() <= 0) return "Quantity must be greater than zero.";
        String suggestedUnit = text(row, "suggestedUnit");
        if (suggestedUnit.isBlank()) return "Unit is required.";
        if (!matchedItemUnit.isBlank() && !suggestedUnit.equals(matchedItemUnit) && !conversionExists) {
            return "Unit conversion is required before creating a package draft.";
        }
        return "";
    }

    static void validateSingleDraftScope(List<ObjectNode> rows) {
        if (rows.isEmpty()) throw new IllegalArgumentException("At least one mapping entry is required.");
        String department = text(rows.get(0), "department");
        String careType = text(rows.get(0), "careType");
        String triggerStage = text(rows.get(0), "triggerStage");
        for (ObjectNode row : rows) {
            if (!department.equals(text(row, "department"))
                || !careType.equals(text(row, "careType"))
                || !triggerStage.equals(text(row, "triggerStage"))) {
                throw new IllegalArgumentException("Package draft mappings must share one department, care type, and trigger stage.");
            }
        }
    }

    static Map<String, Long> summarizeRuleTypes(List<String> ruleTypes) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String ruleType : ruleTypes) counts.put(ruleType, counts.getOrDefault(ruleType, 0L) + 1);
        return counts;
    }

    private void queryCounts(String selectFrom, QuerySpec scope, String groupBy, ArrayNode target) {
        jdbcTemplate.query(
            selectFrom + scope.where() + " GROUP BY " + groupBy + " ORDER BY label ASC",
            resultSet -> {
                while (resultSet.next()) {
                    ObjectNode row = objectMapper.createObjectNode();
                    row.put("label", resultSet.getString("label"));
                    row.put("total", resultSet.getLong("total"));
                    target.add(row);
                }
                return null;
            },
            scope.params().toArray()
        );
    }

    private long count(String sql, List<Object> params) {
        Long value = jdbcTemplate.query(sql, resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L, params.toArray());
        return value == null ? 0L : value;
    }

    private QuerySpec buildFilter(SessionUser user, String ruleType, String status, String department, String keyword) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (user != null && !canReadAll(user)) {
            conditions.add("(e.department_id = ? OR (e.department_id IS NULL AND e.department = ?))");
            params.add(user.activeDepartmentId() == null ? "" : user.activeDepartmentId());
            params.add(user.department() == null ? "" : user.department());
        } else if (department != null && !department.isBlank()) {
            conditions.add("(e.department = ? OR e.department_id = ?)");
            params.add(department);
            params.add(department);
        }
        if (ruleType != null && !ruleType.isBlank()) {
            conditions.add("e.rule_type = ?");
            params.add(ruleType);
        }
        if (status != null && !status.isBlank()) {
            conditions.add("e.status = ?");
            params.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            conditions.add("""
                (e.source_item_name LIKE ? OR e.source_usage LIKE ? OR e.source_note LIKE ?
                 OR e.matched_item_name LIKE ? OR e.suggestion LIKE ?)
                """);
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions) + " ";
        return new QuerySpec(where, params);
    }

    private static boolean canReadAll(SessionUser user) {
        return user != null && List.of("admin", "quality", "manager", "warehouse").contains(user.role());
    }

    private String mappingSelect() {
        return """
            SELECT e.id, e.batch_id, e.source_sheet, e.source_row, e.department, e.department_id,
                   e.source_scenario, e.source_item_name, e.source_usage, e.source_note,
                   e.rule_type, e.care_type, e.trigger_stage, e.condition_text, e.suggested_quantity,
                   e.suggested_unit, e.matched_item_id, e.matched_item_name, e.status,
                   e.import_status, e.suggestion, e.cannot_publish_reason, e.draft_package_id,
                   e.operator_name, e.confirmed_at, e.raw_json,
                   i.enabled matched_item_enabled, i.unit matched_item_unit,
                   COALESCE(i.name, e.matched_item_name) resolved_item_name
            """;
    }

    private ObjectNode loadRow(String id, boolean forUpdate) {
        List<ObjectNode> rows = jdbcTemplate.query(
            mappingSelect() + """
            FROM inventory_mapping_entries e
            LEFT JOIN inventory_items i ON i.id = e.matched_item_id
            WHERE e.id = ?
            """ + (forUpdate ? " FOR UPDATE" : ""),
            (resultSet, rowNumber) -> row(resultSet),
            id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ObjectNode row(ResultSet resultSet) throws SQLException {
        ObjectNode row = readObject(resultSet.getString("raw_json"));
        put(row, "id", resultSet.getString("id"));
        put(row, "batchId", resultSet.getString("batch_id"));
        put(row, "sourceSheet", resultSet.getString("source_sheet"));
        row.put("sourceRow", resultSet.getInt("source_row"));
        put(row, "department", resultSet.getString("department"));
        put(row, "departmentId", resultSet.getString("department_id"));
        put(row, "sourceScenario", resultSet.getString("source_scenario"));
        put(row, "sourceItemName", resultSet.getString("source_item_name"));
        put(row, "sourceUsage", resultSet.getString("source_usage"));
        put(row, "sourceNote", resultSet.getString("source_note"));
        put(row, "ruleType", resultSet.getString("rule_type"));
        put(row, "careType", resultSet.getString("care_type"));
        put(row, "triggerStage", resultSet.getString("trigger_stage"));
        put(row, "condition", resultSet.getString("condition_text"));
        BigDecimal quantity = resultSet.getBigDecimal("suggested_quantity");
        if (quantity != null) putQuantity(row, "suggestedQuantity", quantity);
        put(row, "suggestedUnit", resultSet.getString("suggested_unit"));
        put(row, "matchedItemId", resultSet.getString("matched_item_id"));
        put(row, "matchedItemName", resultSet.getString("resolved_item_name"));
        put(row, "status", resultSet.getString("status"));
        put(row, "importStatus", resultSet.getString("import_status"));
        put(row, "suggestion", resultSet.getString("suggestion"));
        put(row, "cannotPublishReason", resultSet.getString("cannot_publish_reason"));
        put(row, "draftPackageId", resultSet.getString("draft_package_id"));
        put(row, "operator", resultSet.getString("operator_name"));
        put(row, "confirmedAt", resultSet.getString("confirmed_at"));
        boolean enabled = resultSet.getBoolean("matched_item_enabled");
        if (!resultSet.wasNull()) row.put("matchedItemEnabled", enabled);
        put(row, "matchedItemUnit", resultSet.getString("matched_item_unit"));
        return row;
    }

    private ObjectNode decorate(ObjectNode row) {
        boolean enabled = itemEnabled(row);
        String itemUnit = matchedItemUnit(row);
        String reason = cannotCreatePackageDraftReason(
            row,
            enabled,
            itemUnit,
            conversionExists(text(row, "matchedItemId"), text(row, "suggestedUnit"), itemUnit)
        );
        row.put("canCreatePackageDraft", reason.isBlank());
        row.put("cannotPublishReason", reason);
        return row;
    }

    private boolean itemEnabled(JsonNode row) {
        if (row.has("matchedItemEnabled")) return row.path("matchedItemEnabled").asBoolean(false);
        String itemId = text(row, "matchedItemId");
        if (itemId.isBlank()) return false;
        ObjectNode item = item(itemId);
        return item != null && item.path("enabled").asBoolean(true);
    }

    private String matchedItemUnit(JsonNode row) {
        String unit = text(row, "matchedItemUnit");
        if (!unit.isBlank()) return unit;
        String itemId = text(row, "matchedItemId");
        if (itemId.isBlank()) return "";
        ObjectNode item = item(itemId);
        return item == null ? "" : text(item, "unit");
    }

    private boolean conversionExists(String itemId, String sourceUnit, String targetUnit) {
        if (sourceUnit == null || sourceUnit.isBlank()) return false;
        if (targetUnit == null || targetUnit.isBlank() || sourceUnit.equals(targetUnit)) return true;
        Long count = jdbcTemplate.query(
            """
            SELECT COUNT(*) FROM inventory_unit_conversions
            WHERE status = 'active'
              AND (item_id = ? OR item_id IS NULL)
              AND source_unit = ?
              AND target_unit = ?
            """,
            resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L,
            itemId, sourceUnit, targetUnit
        );
        return count != null && count > 0;
    }

    private ObjectNode item(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT id, name, unit, enabled FROM inventory_items WHERE id = ?",
            (resultSet, rowNumber) -> {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("id", resultSet.getString("id"));
                item.put("name", resultSet.getString("name"));
                item.put("unit", resultSet.getString("unit"));
                item.put("enabled", resultSet.getBoolean("enabled"));
                return item;
            },
            itemId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Department resolveDepartment(String departmentId, String department) {
        if (departmentId != null && !departmentId.isBlank()) {
            List<Department> rows = jdbcTemplate.query(
                "SELECT id, name FROM clinic_departments WHERE id = ? LIMIT 1",
                (resultSet, rowNumber) -> new Department(resultSet.getString("id"), resultSet.getString("name")),
                departmentId
            );
            if (!rows.isEmpty()) return rows.get(0);
        }
        if (department != null && !department.isBlank()) {
            List<Department> rows = jdbcTemplate.query(
                "SELECT id, name FROM clinic_departments WHERE name = ? ORDER BY id LIMIT 1",
                (resultSet, rowNumber) -> new Department(resultSet.getString("id"), resultSet.getString("name")),
                department
            );
            if (!rows.isEmpty()) return rows.get(0);
        }
        return new Department(nullToBlank(departmentId), nullToBlank(department));
    }

    private List<String> ids(JsonNode payload) {
        List<String> ids = new ArrayList<>();
        JsonNode source = payload == null ? objectMapper.createObjectNode() : payload;
        if (source.path("ids").isArray()) {
            source.path("ids").forEach(value -> {
                String id = value.asText("");
                if (!id.isBlank() && !ids.contains(id)) ids.add(id);
            });
        }
        String id = text(source, "id");
        if (!id.isBlank() && !ids.contains(id)) ids.add(id);
        if (ids.isEmpty()) throw new IllegalArgumentException("At least one mapping entry id is required.");
        return ids;
    }

    static String normalizeCareType(String careType) {
        if (careType == null || careType.isBlank()) return "";
        return switch (careType.trim().toLowerCase()) {
            case "outpatient", "outpatient_visit", "\u95e8\u8bca" -> "outpatient";
            case "inpatient", "\u4f4f\u9662" -> "inpatient";
            default -> "";
        };
    }

    static String normalizeTriggerStage(String triggerStage) {
        if (triggerStage == null || triggerStage.isBlank()) return "";
        String value = triggerStage.trim();
        if (PENDING_STAGE.equals(value)) return value;
        return value.toUpperCase();
    }

    private static boolean isSupportedCareType(String careType) {
        return "outpatient".equals(careType) || "inpatient".equals(careType);
    }

    private ObjectNode readObject(String rawJson) {
        try {
            JsonNode node = rawJson == null || rawJson.isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(rawJson);
            return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : objectMapper.createObjectNode();
        } catch (Exception error) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize inventory mapping JSON", error);
        }
    }

    private static void put(ObjectNode row, String field, String value) {
        if (value != null) row.put(field, value);
    }

    private static void putQuantity(ObjectNode row, String field, BigDecimal value) {
        if (value != null) row.set(field, DecimalNode.valueOf(value.setScale(2, RoundingMode.HALF_UP)));
    }

    private static BigDecimal quantity(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isNumber() || value.isTextual()) {
            try {
                return new BigDecimal(value.asText()).setScale(2, RoundingMode.HALF_UP);
            } catch (Exception ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private static String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    private static String text(JsonNode node, String key, String fallback) {
        if (node == null) return fallback;
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    public Map<String, Object> asMap(ObjectNode node) {
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private record QuerySpec(String where, List<Object> params) {}
    private record Department(String id, String name) {}
}

