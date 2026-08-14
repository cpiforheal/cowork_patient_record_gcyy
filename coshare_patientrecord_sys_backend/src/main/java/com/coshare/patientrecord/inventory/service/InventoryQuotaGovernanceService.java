package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class InventoryQuotaGovernanceService {
    private static final List<String> SCOPES = List.of("OUTPATIENT", "INPATIENT", "COMBINED", "OTHER");
    private static final List<String> REVIEW_STATUSES = List.of("PENDING", "EXPLAINED", "REVIEWED", "CLOSED");
    private final JdbcTemplate jdbcTemplate;

    public InventoryQuotaGovernanceService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Transactional(readOnly = true)
    public QuotaVersion activeVersion(LocalDate date) {
        List<QuotaVersion> rows = jdbcTemplate.query(
            "SELECT id, version_code, effective_date, status FROM inventory_quota_versions WHERE status = 'ACTIVE' AND effective_date <= ? ORDER BY effective_date DESC LIMIT 1",
            (rs, rowNum) -> new QuotaVersion(rs.getString("id"), rs.getString("version_code"), rs.getDate("effective_date").toLocalDate(), rs.getString("status")), date);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<QuotaRule> rules(String versionId, String departmentKey) {
        return jdbcTemplate.query(
            "SELECT id, version_id, department_key, department_name, source_row, service_group, care_type, material_name, unit, standard_quantity, fixed_adjustment, measurement_scope, enabled FROM inventory_quota_rules WHERE version_id = ? AND department_key = ? AND enabled = 1 ORDER BY source_row, id",
            (rs, rowNum) -> new QuotaRule(rs.getString("id"), rs.getString("version_id"), rs.getString("department_key"), rs.getString("department_name"), rs.getInt("source_row"), rs.getString("service_group"), rs.getString("care_type"), rs.getString("material_name"), rs.getString("unit"), rs.getObject("standard_quantity") == null ? null : rs.getDouble("standard_quantity"), rs.getDouble("fixed_adjustment"), rs.getString("measurement_scope"), rs.getBoolean("enabled")),
            versionId, departmentKey);
    }

    @Transactional(readOnly = true)
    public Map<String, SpecialRule> specialRules(String departmentKey) {
        Map<String, SpecialRule> result = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, department_key, material_name, unit, enabled, admin_note, updated_by, updated_at FROM inventory_special_material_rules WHERE department_key = ? AND enabled = 1", rs -> {
            SpecialRule row = new SpecialRule(rs.getString("id"), rs.getString("department_key"), rs.getString("material_name"), rs.getString("unit"), rs.getBoolean("enabled"), rs.getString("admin_note"), rs.getString("updated_by"), rs.getTimestamp("updated_at").toLocalDateTime().toString());
            result.put(materialKey(row.materialName(), row.unit()), row);
        }, departmentKey);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode governance(LocalDate date) {
        LocalDate queryDate = date == null ? LocalDate.now() : date;
        ArrayNode versions = JsonNodeFactory.instance.arrayNode();
        jdbcTemplate.query("SELECT id, version_code, effective_date, status, created_by, confirmed_by, created_at, updated_at FROM inventory_quota_versions ORDER BY effective_date DESC", rs -> {
            ObjectNode row = versions.addObject();
            row.put("id", rs.getString("id")); row.put("versionCode", rs.getString("version_code")); row.put("effectiveDate", rs.getDate("effective_date").toLocalDate().toString()); row.put("status", rs.getString("status")); row.put("createdBy", rs.getString("created_by")); row.put("confirmedBy", rs.getString("confirmed_by")); row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString()); row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
        });
        QuotaVersion active = activeVersion(queryDate);
        ArrayNode rules = JsonNodeFactory.instance.arrayNode();
        if (active != null) {
            jdbcTemplate.query(
                "SELECT id, version_id, department_key, department_name, source_row, service_group, care_type, material_name, unit, standard_quantity, fixed_adjustment, measurement_scope, enabled FROM inventory_quota_rules WHERE version_id = ? ORDER BY department_name, source_row, id",
                rs -> {
                    rules.add(ruleNode(new QuotaRule(
                        rs.getString("id"), rs.getString("version_id"), rs.getString("department_key"), rs.getString("department_name"),
                        rs.getInt("source_row"), rs.getString("service_group"), rs.getString("care_type"), rs.getString("material_name"),
                        rs.getString("unit"), rs.getObject("standard_quantity") == null ? null : rs.getDouble("standard_quantity"),
                        rs.getDouble("fixed_adjustment"), rs.getString("measurement_scope"), rs.getBoolean("enabled")
                    )));
                },
                active.id()
            );
        }
        ArrayNode specials = JsonNodeFactory.instance.arrayNode();
        jdbcTemplate.query("SELECT id, department_key, material_name, unit, enabled, admin_note, updated_by, updated_at FROM inventory_special_material_rules ORDER BY department_key, material_name, unit", rs -> {
            ObjectNode row = specials.addObject(); row.put("id", rs.getString("id")); row.put("departmentKey", rs.getString("department_key")); row.put("materialName", rs.getString("material_name")); row.put("unit", rs.getString("unit")); row.put("enabled", rs.getBoolean("enabled")); row.put("adminNote", rs.getString("admin_note")); row.put("updatedBy", rs.getString("updated_by")); row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
        });
        ObjectNode result = JsonNodeFactory.instance.objectNode(); result.put("queryDate", queryDate.toString()); if (active == null) result.putNull("activeVersion"); else result.set("activeVersion", versionNode(active)); result.set("versions", versions); result.set("rules", rules); result.set("specialRules", specials); return result;
    }

    @Transactional
    public ObjectNode createVersion(JsonNode payload, SessionUser user) {
        String code = required(payload, "versionCode", 64); LocalDate effectiveDate;
        try { effectiveDate = LocalDate.parse(text(payload, "effectiveDate")); } catch (RuntimeException error) { throw badRequest("生效日期无效"); }
        if (!effectiveDate.isAfter(LocalDate.now())) throw badRequest("新定额版本必须设置为未来生效");
        String baseVersionId = text(payload, "baseVersionId");
        if (baseVersionId.isBlank()) { QuotaVersion active = activeVersion(LocalDate.now()); if (active == null) throw badRequest("当前没有可复制的定额版本"); baseVersionId = active.id(); }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_quota_versions WHERE id = ?", Integer.class, baseVersionId);
        if (count == null || count == 0) throw badRequest("复制来源版本不存在");
        String id = "quota-" + UUID.randomUUID();
        try {
            jdbcTemplate.update("INSERT INTO inventory_quota_versions (id, version_code, effective_date, status, created_by, confirmed_by) VALUES (?, ?, ?, 'ACTIVE', ?, ?)", id, code, effectiveDate, user.username(), user.username());
            jdbcTemplate.update("INSERT INTO inventory_quota_rules (id, version_id, department_key, department_name, source_row, service_group, care_type, material_name, unit, standard_quantity, fixed_adjustment, measurement_scope, enabled) SELECT CONCAT('qr-', UUID()), ?, department_key, department_name, source_row, service_group, care_type, material_name, unit, standard_quantity, fixed_adjustment, measurement_scope, enabled FROM inventory_quota_rules WHERE version_id = ?", id, baseVersionId);
        } catch (RuntimeException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, "版本号或生效日期已存在", error); }
        return governance(effectiveDate);
    }

    @Transactional
    public ObjectNode updateRule(String ruleId, JsonNode payload) {
        LocalDate effectiveDate = jdbcTemplate.query("SELECT v.effective_date FROM inventory_quota_rules r JOIN inventory_quota_versions v ON v.id = r.version_id WHERE r.id = ?", rs -> rs.next() ? rs.getDate(1).toLocalDate() : null, ruleId);
        if (effectiveDate == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定额规则不存在");
        if (!effectiveDate.isAfter(LocalDate.now())) throw badRequest("已生效版本已冻结，请复制为未来版本后修改");
        String scope = required(payload, "measurementScope", 24).toUpperCase(); if (!SCOPES.contains(scope)) throw badRequest("计量范围无效");
        Double standard = nullableNonNegative(payload.get("standardQuantity")); double adjustment = finite(payload.get("fixedAdjustment"), 0); boolean enabled = payload.path("enabled").asBoolean(true);
        jdbcTemplate.update("UPDATE inventory_quota_rules SET standard_quantity = ?, fixed_adjustment = ?, measurement_scope = ?, enabled = ? WHERE id = ?", standard, adjustment, scope, enabled, ruleId);
        return governance(effectiveDate);
    }

    @Transactional
    public ObjectNode upsertSpecial(JsonNode payload, SessionUser user) {
        String departmentKey = required(payload, "departmentKey", 64); String materialName = required(payload, "materialName", 255); String unit = text(payload, "unit"); String note = text(payload, "adminNote");
        if (unit.length() > 64 || note.length() > 1000) throw badRequest("特殊耗材单位或说明过长");
        boolean enabled = payload.path("enabled").asBoolean(true);
        jdbcTemplate.update("INSERT INTO inventory_special_material_rules (id, department_key, material_name, unit, enabled, admin_note, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), admin_note = VALUES(admin_note), updated_by = VALUES(updated_by)", "special-" + UUID.randomUUID(), departmentKey, materialName, unit, enabled, note, user.username());
        return governance(LocalDate.now());
    }

    @Transactional(readOnly = true)
    public Map<String, ReviewRecord> reviews(LocalDate from, LocalDate to) {
        Map<String, ReviewRecord> result = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT business_date, department_key, line_key, review_status, review_note, reviewer_username, reviewer_name, reviewed_at FROM inventory_quota_review_records WHERE business_date BETWEEN ? AND ?", rs -> { result.put(reviewKey(rs.getDate("business_date").toLocalDate(), rs.getString("department_key"), rs.getString("line_key")), new ReviewRecord(rs.getString("review_status"), rs.getString("review_note"), rs.getString("reviewer_username"), rs.getString("reviewer_name"), rs.getTimestamp("reviewed_at").toLocalDateTime().toString())); }, from, to);
        return result;
    }

    @Transactional
    public ObjectNode saveReview(JsonNode payload, SessionUser user) {
        LocalDate businessDate; try { businessDate = LocalDate.parse(text(payload, "businessDate")); } catch (RuntimeException error) { throw badRequest("业务日期无效"); }
        String departmentKey = required(payload, "departmentKey", 64); String lineKey = required(payload, "lineKey", 128); String materialName = required(payload, "materialName", 255); String unit = text(payload, "unit"); String status = required(payload, "reviewStatus", 24).toUpperCase(); String note = text(payload, "reviewNote");
        if (!REVIEW_STATUSES.contains(status)) throw badRequest("复核状态无效"); if (note.length() > 2000) throw badRequest("复核备注不能超过 2000 字");
        jdbcTemplate.update("INSERT INTO inventory_quota_review_records (id, business_date, department_key, line_key, material_name, unit, review_status, review_note, reviewer_username, reviewer_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE material_name = VALUES(material_name), unit = VALUES(unit), review_status = VALUES(review_status), review_note = VALUES(review_note), reviewer_username = VALUES(reviewer_username), reviewer_name = VALUES(reviewer_name), reviewed_at = CURRENT_TIMESTAMP(3)", "review-" + UUID.randomUUID(), businessDate, departmentKey, lineKey, materialName, unit, status, note, user.username(), user.name());
        ObjectNode result = JsonNodeFactory.instance.objectNode(); result.put("businessDate", businessDate.toString()); result.put("departmentKey", departmentKey); result.put("lineKey", lineKey); result.put("reviewStatus", status); result.put("reviewNote", note); result.put("reviewerUsername", user.username()); result.put("reviewerName", user.name()); return result;
    }

    public static String materialKey(String materialName, String unit) { return materialName.trim() + "\u0000" + unit.trim(); }
    public static String reviewKey(LocalDate date, String departmentKey, String lineKey) { return date + "\u0000" + departmentKey + "\u0000" + lineKey; }
    private static ObjectNode versionNode(QuotaVersion version) { ObjectNode row = JsonNodeFactory.instance.objectNode(); row.put("id", version.id()); row.put("versionCode", version.versionCode()); row.put("effectiveDate", version.effectiveDate().toString()); row.put("status", version.status()); return row; }
    private static ObjectNode ruleNode(QuotaRule rule) { ObjectNode row = JsonNodeFactory.instance.objectNode(); row.put("id", rule.id()); row.put("versionId", rule.versionId()); row.put("departmentKey", rule.departmentKey()); row.put("departmentName", rule.departmentName()); row.put("sourceRow", rule.sourceRow()); row.put("serviceGroup", rule.serviceGroup()); row.put("careType", rule.careType()); row.put("materialName", rule.materialName()); row.put("unit", rule.unit()); if (rule.standardQuantity() == null) row.putNull("standardQuantity"); else row.put("standardQuantity", rule.standardQuantity()); row.put("fixedAdjustment", rule.fixedAdjustment()); row.put("measurementScope", rule.measurementScope()); row.put("enabled", rule.enabled()); return row; }
    private static String required(JsonNode node, String field, int max) { String value = text(node, field); if (value.isBlank() || value.length() > max) throw badRequest(field + " 无效"); return value; }
    private static String text(JsonNode node, String field) { return node == null ? "" : node.path(field).asText("").trim(); }
    private static Double nullableNonNegative(JsonNode value) { if (value == null || value.isNull()) return null; if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < 0) throw badRequest("定额必须为非负数"); return value.asDouble(); }
    private static double finite(JsonNode value, double fallback) { if (value == null || value.isNull()) return fallback; if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw badRequest("固定调整值无效"); return value.asDouble(); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record QuotaVersion(String id, String versionCode, LocalDate effectiveDate, String status) {}
    public record QuotaRule(String id, String versionId, String departmentKey, String departmentName, int sourceRow, String serviceGroup, String careType, String materialName, String unit, Double standardQuantity, double fixedAdjustment, String measurementScope, boolean enabled) {}
    public record SpecialRule(String id, String departmentKey, String materialName, String unit, boolean enabled, String adminNote, String updatedBy, String updatedAt) {}
    public record ReviewRecord(String reviewStatus, String reviewNote, String reviewerUsername, String reviewerName, String reviewedAt) {}
}
