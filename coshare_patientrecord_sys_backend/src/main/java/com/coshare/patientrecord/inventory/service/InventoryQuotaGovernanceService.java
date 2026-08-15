package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
    private final ObjectMapper objectMapper;

    public InventoryQuotaGovernanceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

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
    public QuotaVersion versionById(String versionId) {
        if (versionId == null || versionId.isBlank()) return null;
        List<QuotaVersion> rows = jdbcTemplate.query(
            "SELECT id, version_code, effective_date, status FROM inventory_quota_versions WHERE id = ?",
            (rs, rowNum) -> new QuotaVersion(rs.getString("id"), rs.getString("version_code"), rs.getDate("effective_date").toLocalDate(), rs.getString("status")), versionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public QuotaVersion editableVersion() {
        List<QuotaVersion> rows = jdbcTemplate.query(
            "SELECT id, version_code, effective_date, status FROM inventory_quota_versions ORDER BY effective_date DESC, created_at DESC LIMIT 1",
            (rs, rowNum) -> new QuotaVersion(rs.getString("id"), rs.getString("version_code"), rs.getDate("effective_date").toLocalDate(), rs.getString("status")));
        if (rows.isEmpty()) return null;
        QuotaVersion latest = rows.get(0);
        return latest.effectiveDate().isAfter(LocalDate.now()) ? latest : null;
    }

    @Transactional(readOnly = true)
    public ObjectNode governance(LocalDate date) { return governance(date, null); }

    @Transactional(readOnly = true)
    public ObjectNode governance(LocalDate date, String versionId) {
        LocalDate queryDate = date == null ? LocalDate.now() : date;
        ArrayNode versions = JsonNodeFactory.instance.arrayNode();
        jdbcTemplate.query("SELECT id, version_code, effective_date, status, created_by, confirmed_by, created_at, updated_at FROM inventory_quota_versions ORDER BY effective_date DESC", rs -> {
            ObjectNode row = versions.addObject();
            row.put("id", rs.getString("id")); row.put("versionCode", rs.getString("version_code")); row.put("effectiveDate", rs.getDate("effective_date").toLocalDate().toString()); row.put("status", rs.getString("status")); row.put("createdBy", rs.getString("created_by")); row.put("confirmedBy", rs.getString("confirmed_by")); row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString()); row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
        });
        QuotaVersion active = versionId == null || versionId.isBlank() ? activeVersion(queryDate) : versionById(versionId);
        if (versionId != null && !versionId.isBlank() && active == null) throw badRequest("定额版本不存在");
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
        LocalDate effectiveDate;
        try { effectiveDate = LocalDate.parse(text(payload, "effectiveDate")); } catch (RuntimeException error) { throw badRequest("生效日期无效"); }
        if (!effectiveDate.isAfter(LocalDate.now())) throw badRequest("新定额版本必须设置为未来生效");
        String code = text(payload, "versionCode");
        boolean autoCode = code.isBlank();
        if (autoCode) code = autoVersionCode(effectiveDate);
        String baseVersionId = text(payload, "baseVersionId");
        if (baseVersionId.isBlank()) { QuotaVersion active = activeVersion(LocalDate.now()); if (active == null) throw badRequest("当前没有可复制的定额版本"); baseVersionId = active.id(); }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_quota_versions WHERE id = ?", Integer.class, baseVersionId);
        if (count == null || count == 0) throw badRequest("复制来源版本不存在");
        String id = "quota-" + UUID.randomUUID();
        int attempts = 0;
        while (true) {
            try {
                jdbcTemplate.update("INSERT INTO inventory_quota_versions (id, version_code, effective_date, status, created_by, confirmed_by) VALUES (?, ?, ?, 'ACTIVE', ?, ?)", id, code, effectiveDate, user.username(), user.username());
                break;
            } catch (RuntimeException error) {
                if (!autoCode || ++attempts > 3) throw new ResponseStatusException(HttpStatus.CONFLICT, "版本号或生效日期已存在", error);
                code = autoVersionCode(effectiveDate) + "-" + (attempts + 1);
            }
        }
        jdbcTemplate.update("INSERT INTO inventory_quota_rules (id, version_id, department_key, department_name, source_row, service_group, care_type, material_name, unit, standard_quantity, fixed_adjustment, measurement_scope, enabled) SELECT CONCAT('qr-', UUID()), ?, department_key, department_name, source_row, service_group, care_type, material_name, unit, standard_quantity, fixed_adjustment, measurement_scope, enabled FROM inventory_quota_rules WHERE version_id = ?", id, baseVersionId);
        return governance(effectiveDate);
    }

    private static String autoVersionCode(LocalDate effectiveDate) { return "Q-" + effectiveDate.toString().replace("-", ""); }

    @Transactional(readOnly = true)
    public QuotaVersion versionOfRule(String ruleId) {
        List<QuotaVersion> rows = jdbcTemplate.query(
            "SELECT v.id, v.version_code, v.effective_date, v.status FROM inventory_quota_rules r JOIN inventory_quota_versions v ON v.id = r.version_id WHERE r.id = ?",
            (rs, rowNum) -> new QuotaVersion(rs.getString("id"), rs.getString("version_code"), rs.getDate("effective_date").toLocalDate(), rs.getString("status")), ruleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private QuotaVersion requireEditableVersion(QuotaVersion version) {
        if (version == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "定额规则不存在");
        if (!version.effectiveDate().isAfter(LocalDate.now())) throw badRequest("已生效版本已冻结，请复制为未来版本后修改");
        return version;
    }

    private void applyRuleUpdate(String ruleId, JsonNode payload) {
        String scope = required(payload, "measurementScope", 24).toUpperCase(); if (!SCOPES.contains(scope)) throw badRequest("计量范围无效");
        Double standard = nullableNonNegative(payload.get("standardQuantity")); double adjustment = finite(payload.get("fixedAdjustment"), 0); boolean enabled = payload.path("enabled").asBoolean(true);
        jdbcTemplate.update("UPDATE inventory_quota_rules SET standard_quantity = ?, fixed_adjustment = ?, measurement_scope = ?, enabled = ? WHERE id = ?", standard, adjustment, scope, enabled, ruleId);
    }

    @Transactional
    public ObjectNode updateRule(String ruleId, JsonNode payload) {
        QuotaVersion version = requireEditableVersion(versionOfRule(ruleId));
        applyRuleUpdate(ruleId, payload);
        return governance(version.effectiveDate(), version.id());
    }

    @Transactional
    public ObjectNode updateRulesBatch(JsonNode payload) {
        JsonNode rules = payload == null ? null : payload.path("rules");
        if (rules == null || !rules.isArray() || rules.isEmpty()) throw badRequest("没有需要保存的定额规则");
        LocalDate latestEffective = null; String latestVersionId = null;
        for (JsonNode rule : rules) {
            String ruleId = text(rule, "id");
            if (ruleId.isBlank()) throw badRequest("定额规则 id 缺失");
            QuotaVersion version = requireEditableVersion(versionOfRule(ruleId));
            applyRuleUpdate(ruleId, rule);
            if (latestEffective == null || version.effectiveDate().isAfter(latestEffective)) { latestEffective = version.effectiveDate(); latestVersionId = version.id(); }
        }
        return governance(latestEffective, latestVersionId);
    }

    private QuotaVersion resolveConsoleVersion(String versionId) {
        if (versionId != null && !versionId.isBlank()) return requireEditableVersion(versionById(versionId));
        QuotaVersion editable = editableVersion();
        if (editable == null) throw badRequest("请先创建未来生效的定额版本");
        return requireEditableVersion(editable);
    }

    private QuotaRule insertRule(QuotaVersion version, JsonNode payload) {
        String departmentKey = required(payload, "departmentKey", 64);
        String departmentName = InventoryDepartmentDraftService.departmentDirectory().get(departmentKey);
        if (departmentName == null) throw badRequest("未知科室：" + departmentKey);
        String materialName = required(payload, "materialName", 255);
        String unit = text(payload, "unit");
        if (unit.length() > 64) throw badRequest("耗材单位过长");
        String serviceGroup = text(payload, "serviceGroup");
        if (serviceGroup.length() > 128) throw badRequest("服务项目过长");
        String careType = text(payload, "careType");
        if (careType.length() > 32) throw badRequest("照护类型过长");
        String scope = required(payload, "measurementScope", 24).toUpperCase(); if (!SCOPES.contains(scope)) throw badRequest("计量范围无效");
        Double standard = nullableNonNegative(payload.get("standardQuantity")); double adjustment = finite(payload.get("fixedAdjustment"), 0); boolean enabled = payload.path("enabled").asBoolean(true);
        Integer duplicate = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inventory_quota_rules WHERE version_id = ? AND department_key = ? AND material_name = ? AND unit = ?", Integer.class, version.id(), departmentKey, materialName, unit);
        if (duplicate != null && duplicate > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, departmentName + " 已存在同名耗材的定额规则");
        Integer maxRow = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(source_row), 0) FROM inventory_quota_rules WHERE version_id = ? AND department_key = ?", Integer.class, version.id(), departmentKey);
        int sourceRow = (maxRow == null ? 0 : maxRow) + 1;
        String id = "qr-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO inventory_quota_rules (id, version_id, department_key, department_name, source_row, service_group, care_type, material_name, unit, standard_quantity, fixed_adjustment, measurement_scope, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, version.id(), departmentKey, departmentName, sourceRow, serviceGroup, careType, materialName, unit, standard, adjustment, scope, enabled);
        return new QuotaRule(id, version.id(), departmentKey, departmentName, sourceRow, serviceGroup, careType, materialName, unit, standard, adjustment, scope, enabled);
    }

    @Transactional
    public ObjectNode createRule(JsonNode payload) {
        QuotaVersion version = resolveConsoleVersion(text(payload, "versionId"));
        insertRule(version, payload);
        return governance(version.effectiveDate(), version.id());
    }

    @Transactional
    public ObjectNode deleteRule(String ruleId) {
        QuotaVersion version = requireEditableVersion(versionOfRule(ruleId));
        jdbcTemplate.update("DELETE FROM inventory_quota_rules WHERE id = ?", ruleId);
        cascadeRemoveRuleFromTodaysDrafts(ruleId);
        return governance(version.effectiveDate(), version.id());
    }

    /** 删除规则后，当日（含未来）草稿中尚未填报实际使用量的对应行直接移除；已填报行保留，由下次保存时降级为补充行快照。 */
    private void cascadeRemoveRuleFromTodaysDrafts(String ruleId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT id, raw_json FROM inventory_department_daily_drafts WHERE business_date >= ?",
            LocalDate.now()
        );
        for (Map<String, Object> row : rows) {
            JsonNode parsed;
            try { parsed = objectMapper.readTree(String.valueOf(row.get("raw_json"))); } catch (Exception ignored) { continue; }
            if (!parsed.isObject()) continue;
            JsonNode lines = parsed.path("lines");
            if (!lines.isArray() || lines.isEmpty()) continue;
            boolean changed = false;
            ArrayNode filtered = JsonNodeFactory.instance.arrayNode();
            for (JsonNode line : lines) {
                boolean matches = ruleId.equals(text(line, "lineKey")) || ruleId.equals(text(line, "id"));
                boolean filled = line.path("actualQuantity").isNumber();
                if (matches && !filled) { changed = true; continue; }
                filtered.add(line);
            }
            if (!changed) continue;
            try {
                jdbcTemplate.update(
                    "UPDATE inventory_department_daily_drafts SET raw_json = CAST(? AS JSON) WHERE id = ?",
                    objectMapper.writeValueAsString(((ObjectNode) parsed).set("lines", filtered)),
                    String.valueOf(row.get("id"))
                );
            } catch (Exception ignored) {
                // 级联清理尽力而为；失败时该行仍会在下次保存时降级为补充行
            }
        }
    }

    @Transactional
    public ObjectNode consoleSave(JsonNode payload, SessionUser user) {
        JsonNode updates = payload.path("updates");
        JsonNode creates = payload.path("creates");
        JsonNode deletes = payload.path("deletes");
        boolean hasChanges = (updates.isArray() && !updates.isEmpty()) || (creates.isArray() && !creates.isEmpty()) || (deletes.isArray() && !deletes.isEmpty());
        if (!hasChanges) throw badRequest("没有需要保存的变更");
        String requestedVersionId = text(payload, "versionId");
        QuotaVersion target;
        Map<String, String> ruleIdRemap = Map.of();
        if (!requestedVersionId.isBlank()) {
            target = requireEditableVersion(versionById(requestedVersionId));
        } else {
            target = editableVersion();
            if (target == null) {
                LocalDate effectiveDate;
                String requested = text(payload, "effectiveDate");
                if (requested.isBlank()) effectiveDate = LocalDate.now().plusDays(1);
                else { try { effectiveDate = LocalDate.parse(requested); } catch (RuntimeException error) { throw badRequest("生效日期无效"); } }
                if (!effectiveDate.isAfter(LocalDate.now())) throw badRequest("新定额版本必须设置为未来生效");
                String baseVersionId = text(payload, "baseVersionId");
                QuotaVersion base = baseVersionId.isBlank() ? activeVersion(LocalDate.now()) : versionById(baseVersionId);
                if (base == null) throw badRequest("当前没有可复制的定额版本");
                ObjectNode created = JsonNodeFactory.instance.objectNode();
                created.put("effectiveDate", effectiveDate.toString());
                String requestedCode = text(payload, "versionCode");
                if (!requestedCode.isBlank()) created.put("versionCode", requestedCode);
                createVersion(created, user);
                target = requireEditableVersion(editableVersion());
                if (target == null) throw badRequest("创建未来定额版本失败");
                ruleIdRemap = ruleIdRemap(base.id(), target.id());
            }
        }
        QuotaVersion finalTarget = target;
        if (updates.isArray()) {
            for (JsonNode rule : updates) {
                String ruleId = text(rule, "id");
                if (ruleId.isBlank()) throw badRequest("定额规则 id 缺失");
                String resolvedId = ruleIdRemap.getOrDefault(ruleId, ruleId);
                QuotaVersion ruleVersion = requireEditableVersion(versionOfRule(resolvedId));
                if (!ruleVersion.id().equals(finalTarget.id())) throw badRequest("存在不属于目标版本的定额规则，请刷新后重试");
                applyRuleUpdate(resolvedId, rule);
            }
        }
        if (creates.isArray()) for (JsonNode create : creates) insertRule(finalTarget, create);
        if (deletes.isArray()) {
            for (JsonNode delete : deletes) {
                String ruleId = delete.asText("");
                if (ruleId.isBlank()) continue;
                String resolvedId = ruleIdRemap.getOrDefault(ruleId, ruleId);
                QuotaVersion ruleVersion = requireEditableVersion(versionOfRule(resolvedId));
                if (!ruleVersion.id().equals(finalTarget.id())) throw badRequest("存在不属于目标版本的定额规则，请刷新后重试");
                jdbcTemplate.update("DELETE FROM inventory_quota_rules WHERE id = ?", resolvedId);
                cascadeRemoveRuleFromTodaysDrafts(resolvedId);
            }
        }
        ObjectNode result = governance(finalTarget.effectiveDate(), finalTarget.id());
        result.put("savedVersionId", finalTarget.id());
        result.put("savedVersionCode", finalTarget.versionCode());
        result.put("savedEffectiveDate", finalTarget.effectiveDate().toString());
        if (payload.path("applyToday").asBoolean(false)) result.put("applyTodayRequested", true);
        return result;
    }

    /** 自动建版后，把前端基于旧版本提交的规则 id 映射到新版本规则 id（按科室 + 行号对齐）。 */
    private Map<String, String> ruleIdRemap(String fromVersionId, String toVersionId) {
        Map<String, String> fromKeys = ruleKeysByVersion(fromVersionId);
        Map<String, String> toKeys = ruleKeysByVersion(toVersionId);
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : fromKeys.entrySet()) {
            String toId = toKeys.get(entry.getKey());
            if (toId != null) result.put(entry.getValue(), toId);
        }
        return result;
    }

    private Map<String, String> ruleKeysByVersion(String versionId) {
        Map<String, String> result = new java.util.HashMap<>();
        jdbcTemplate.query(
            "SELECT id, department_key, source_row FROM inventory_quota_rules WHERE version_id = ?",
            (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                while (rs.next()) result.put(rs.getString("department_key") + "\u0000" + rs.getInt("source_row"), rs.getString("id"));
                return null;
            },
            versionId
        );
        return result;
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

    @Transactional(readOnly = true)
    public byte[] exportGovernanceXlsx(LocalDate date, String versionId, SessionUser user) {
        ObjectNode report = governance(date, versionId);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            applyThinBorders(headerStyle);
            CellStyle dataStyle = workbook.createCellStyle();
            applyThinBorders(dataStyle);
            CellStyle numberStyle = workbook.createCellStyle();
            applyThinBorders(numberStyle);
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("0.##"));

            JsonNode active = report.path("activeVersion");
            String versionCode = active.isObject() ? active.path("versionCode").asText("-") : "-";
            String effectiveDate = active.isObject() ? active.path("effectiveDate").asText("-") : "-";
            String versionStatus = active.isObject() ? active.path("status").asText("-") : "-";

            Sheet rulesSheet = workbook.createSheet("定额规则总表");
            int row = writeXlsxRow(rulesSheet, 0, titleStyle, "全院耗材每人次定额总表");
            row = writeXlsxRow(rulesSheet, row, null, "定额版本", versionCode, "生效日期", effectiveDate, "版本状态", versionStatus);
            row = writeXlsxRow(rulesSheet, row, null, "查询日期", report.path("queryDate").asText("-"), "导出时间", LocalDateTime.now().withNano(0).toString().replace("T", " "), "导出人", user.name() + "（" + user.username() + "）");
            row++;
            row = writeXlsxRow(rulesSheet, row, headerStyle, "科室", "服务项目", "照护类型", "耗材名称", "单位", "每人次定额", "固定调整", "计量范围", "排序行", "状态");
            int headerRowIndex = row - 1;
            for (JsonNode rule : report.path("rules")) {
                Row dataRow = rulesSheet.createRow(row++);
                writeTextCell(dataRow, 0, dataStyle, rule.path("departmentName").asText(""));
                writeTextCell(dataRow, 1, dataStyle, rule.path("serviceGroup").asText(""));
                writeTextCell(dataRow, 2, dataStyle, rule.path("careType").asText(""));
                writeTextCell(dataRow, 3, dataStyle, rule.path("materialName").asText(""));
                writeTextCell(dataRow, 4, dataStyle, rule.path("unit").asText(""));
                JsonNode standard = rule.path("standardQuantity");
                Cell standardCell = dataRow.createCell(5);
                standardCell.setCellStyle(numberStyle);
                if (standard.isNumber()) standardCell.setCellValue(standard.asDouble()); else standardCell.setBlank();
                Cell adjustmentCell = dataRow.createCell(6);
                adjustmentCell.setCellStyle(numberStyle);
                adjustmentCell.setCellValue(rule.path("fixedAdjustment").asDouble(0));
                writeTextCell(dataRow, 7, dataStyle, measurementScopeLabel(rule.path("measurementScope").asText("")));
                Cell sourceRowCell = dataRow.createCell(8);
                sourceRowCell.setCellStyle(numberStyle);
                sourceRowCell.setCellValue(rule.path("sourceRow").asInt(0));
                writeTextCell(dataRow, 9, dataStyle, rule.path("enabled").asBoolean(true) ? "启用" : "停用");
            }
            rulesSheet.createFreezePane(0, headerRowIndex + 1);
            int[] rulesWidths = { 14, 22, 12, 26, 8, 12, 10, 16, 8, 8 };
            for (int i = 0; i < rulesWidths.length; i++) rulesSheet.setColumnWidth(i, rulesWidths[i] * 256);

            Sheet versionsSheet = workbook.createSheet("版本清单");
            row = writeXlsxRow(versionsSheet, 0, titleStyle, "定额版本清单（按生效日期倒序）");
            row = writeXlsxRow(versionsSheet, row, headerStyle, "版本号", "生效日期", "状态", "创建人", "确认人", "创建时间", "更新时间");
            for (JsonNode version : report.path("versions")) {
                row = writeXlsxRow(versionsSheet, row, dataStyle,
                    version.path("versionCode").asText(""),
                    version.path("effectiveDate").asText(""),
                    version.path("status").asText(""),
                    version.path("createdBy").asText(""),
                    version.path("confirmedBy").asText(""),
                    version.path("createdAt").asText("").replace("T", " "),
                    version.path("updatedAt").asText("").replace("T", " "));
            }
            int[] versionsWidths = { 18, 14, 10, 14, 14, 22, 22 };
            for (int i = 0; i < versionsWidths.length; i++) versionsSheet.setColumnWidth(i, versionsWidths[i] * 256);

            Sheet glossarySheet = workbook.createSheet("口径说明");
            row = writeXlsxRow(glossarySheet, 0, titleStyle, "口径说明");
            row = writeXlsxRow(glossarySheet, row, headerStyle, "字段", "口径");
            String[][] glossary = {
                { "每人次定额", "单个患者人次（按计量范围口径）应消耗的耗材数量；为空表示该耗材不参与自动测算。" },
                { "固定调整", "在“定额 × 人次”基础上额外增减的数量，可为负数。" },
                { "理论使用量", "每人次定额 × 计量人次 + 固定调整；补充行按实际填报值计。" },
                { "计量范围-OUTPATIENT", "按该服务项目的门诊人次计算。" },
                { "计量范围-INPATIENT", "按该服务项目的住院床日计算。" },
                { "计量范围-COMBINED", "按门诊人次与住院床日合并计算。" },
                { "计量范围-OTHER", "人次在科室日报中手工填报。" },
                { "导出口径", "导出内容为“查询日期/指定版本”下生效的全部定额规则；已生效历史版本只读，调整需在总控制台保存为未来版本。" }
            };
            for (String[] entry : glossary) row = writeXlsxRow(glossarySheet, row, dataStyle, entry[0], entry[1]);
            glossarySheet.setColumnWidth(0, 26 * 256);
            glossarySheet.setColumnWidth(1, 110 * 256);

            workbook.write(output);
            return output.toByteArray();
        } catch (Exception error) {
            throw new IllegalStateException("定额总表导出失败", error);
        }
    }

    private static String measurementScopeLabel(String scope) {
        return switch (scope == null ? "" : scope) {
            case "OUTPATIENT" -> "门诊人次";
            case "INPATIENT" -> "住院床日";
            case "COMBINED" -> "门诊+住院";
            case "OTHER" -> "手工人次";
            default -> scope == null ? "" : scope;
        };
    }

    private static void applyThinBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private static int writeXlsxRow(Sheet sheet, int rowIndex, CellStyle style, Object... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            Object value = values[i];
            if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else cell.setCellValue(value == null ? "" : String.valueOf(value));
            if (style != null) cell.setCellStyle(style);
        }
        return rowIndex + 1;
    }

    private static void writeTextCell(Row row, int column, CellStyle style, String value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

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
