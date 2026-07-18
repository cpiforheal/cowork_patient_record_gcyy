package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
@Profile("mysql")
public class InventoryPackageService {

    private static final String PER_VISIT = "per_visit";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryRepository repository;

    public InventoryPackageService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    public ArrayNode readPackages() {
        ArrayNode packages = objectMapper.createArrayNode();
        jdbcTemplate.query(
            """
            SELECT id, name, department, department_id, care_type, trigger_stage, version_no, status,
                   effective_date, operator_name, created_at, raw_json
            FROM inventory_packages
            ORDER BY department ASC, care_type ASC, trigger_stage ASC, version_no DESC, created_at DESC
            """,
            resultSet -> {
                while (resultSet.next()) {
                    ObjectNode row = packageRow(resultSet);
                    row.set("lines", readPackageLines(row.path("id").asText()));
                    packages.add(row);
                }
                return null;
            }
        );
        return packages;
    }

    public ArrayNode readConsumptionEvents() {
        ArrayNode events = objectMapper.createArrayNode();
        jdbcTemplate.query(
            """
            SELECT id, command_id, encounter_id, case_token, route, department, department_id,
                   trigger_stage, completion_version, visit_date, package_id, status, error_message,
                   event_kind, reversal_of_event_id, operator_name, created_at, raw_json
            FROM inventory_consumption_events
            ORDER BY created_at DESC, id DESC
            """,
            resultSet -> {
                while (resultSet.next()) {
                    ObjectNode row = consumptionEventRow(resultSet);
                    row.set("details", readConsumptionDetails(row.path("id").asText()));
                    events.add(row);
                }
                return null;
            }
        );
        return events;
    }

    private ObjectNode packageRow(ResultSet resultSet) throws SQLException {
        ObjectNode row = readObject(resultSet.getString("raw_json"));
        putText(row, "id", resultSet.getString("id"));
        putText(row, "name", resultSet.getString("name"));
        putText(row, "department", resultSet.getString("department"));
        putText(row, "departmentId", resultSet.getString("department_id"));
        putText(row, "careType", resultSet.getString("care_type"));
        putText(row, "triggerStage", resultSet.getString("trigger_stage"));
        row.put("version", resultSet.getInt("version_no"));
        putText(row, "status", resultSet.getString("status"));
        putText(row, "effectiveDate", resultSet.getString("effective_date"));
        putText(row, "operator", resultSet.getString("operator_name"));
        putText(row, "createdAt", resultSet.getString("created_at"));
        return row;
    }

    private ObjectNode consumptionEventRow(ResultSet resultSet) throws SQLException {
        ObjectNode row = readObject(resultSet.getString("raw_json"));
        putText(row, "id", resultSet.getString("id"));
        putText(row, "commandId", resultSet.getString("command_id"));
        putText(row, "encounterId", resultSet.getString("encounter_id"));
        putText(row, "caseToken", resultSet.getString("case_token"));
        putText(row, "route", resultSet.getString("route"));
        putText(row, "department", resultSet.getString("department"));
        putText(row, "departmentId", resultSet.getString("department_id"));
        putText(row, "triggerStage", resultSet.getString("trigger_stage"));
        long completionVersion = resultSet.getLong("completion_version");
        if (!resultSet.wasNull()) row.put("completionVersion", completionVersion);
        putText(row, "visitDate", resultSet.getString("visit_date"));
        putText(row, "packageId", resultSet.getString("package_id"));
        putText(row, "status", resultSet.getString("status"));
        putText(row, "errorMessage", resultSet.getString("error_message"));
        putText(row, "eventKind", resultSet.getString("event_kind"));
        putText(row, "reversalOfEventId", resultSet.getString("reversal_of_event_id"));
        putText(row, "operator", resultSet.getString("operator_name"));
        putText(row, "createdAt", resultSet.getString("created_at"));
        return row;
    }

    private void putText(ObjectNode row, String field, String value) {
        if (value != null) row.put(field, value);
    }

    @Transactional
    public ObjectNode saveDraft(JsonNode payload, SessionUser user) {
        ObjectNode input = repository.object(payload).deepCopy();
        String department = repository.text(input, "department", user.department());
        if (!isManager(user) && !department.equals(user.department())) department = user.department();
        String careType = normalizeCareType(repository.text(input, "careType", repository.text(input, "route")));
        String triggerStage = normalizeTriggerStage(repository.text(input, "triggerStage", "REVIEW"));
        String departmentId = resolveDepartmentId(repository.text(input, "departmentId"), department);
        String name = repository.text(input, "name");
        ArrayNode lines = normalizeLines(input.path("lines"));
        if (department.isBlank()) throw new IllegalArgumentException("科室不能为空");
        if (careType.isBlank()) throw new IllegalArgumentException("就诊类型必须是门诊或住院");
        if (name.isBlank()) throw new IllegalArgumentException("套餐名称不能为空");
        if (lines.isEmpty()) throw new IllegalArgumentException("套餐至少需要一项物资");

        String id = repository.text(input, "id");
        ObjectNode existing = id.isBlank() ? null : findPackage(id);
        if (existing != null && "enabled".equals(repository.text(existing, "status"))) {
            throw new IllegalArgumentException("已启用套餐不能直接修改，请新建版本");
        }
        if (id.isBlank()) id = "pkg-" + UUID.randomUUID();
        int version = existing == null ? nextVersion(departmentId, careType, triggerStage) : existing.path("version").asInt(1);
        ObjectNode row = input.deepCopy();
        row.put("id", id);
        row.put("name", name);
        row.put("department", department);
        row.put("departmentId", departmentId);
        row.put("careType", careType);
        row.put("triggerStage", triggerStage);
        row.put("version", version);
        row.put("status", "draft");
        row.put("operator", user.name());
        row.put("createdAt", existing == null ? repository.now() : repository.text(existing, "createdAt", repository.now()));
        row.set("lines", lines);

        jdbcTemplate.update(
            """
            INSERT INTO inventory_packages
              (id, name, department, department_id, care_type, trigger_stage, version_no, status, effective_date, operator_name, raw_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE name = VALUES(name), department = VALUES(department), care_type = VALUES(care_type),
              department_id = VALUES(department_id), trigger_stage = VALUES(trigger_stage),
              version_no = VALUES(version_no), status = 'draft', effective_date = VALUES(effective_date),
              operator_name = VALUES(operator_name), raw_json = VALUES(raw_json)
            """,
            id, name, department, departmentId, careType, triggerStage, version, repository.text(row, "effectiveDate"), user.name(), toJson(row),
            repository.text(row, "createdAt")
        );
        jdbcTemplate.update("DELETE FROM inventory_package_lines WHERE package_id = ?", id);
        for (JsonNode lineNode : lines) {
            ObjectNode line = (ObjectNode) lineNode;
            jdbcTemplate.update(
                "INSERT INTO inventory_package_lines (id, package_id, item_id, quantity, consumption_mode, raw_json) VALUES (?, ?, ?, ?, ?, ?)",
                repository.text(line, "id"), id, repository.text(line, "itemId"), repository.quantity(line, "quantity"),
                repository.text(line, "consumptionMode", "per_visit"), toJson(line)
            );
        }
        return row;
    }

    @Transactional
    public ObjectNode enable(String id, SessionUser user) {
        ObjectNode target = findPackage(id);
        if (target == null) throw new IllegalArgumentException("套餐不存在");
        if (!isManager(user) && !user.department().equals(repository.text(target, "department"))) {
            throw new IllegalArgumentException("不能启用其他科室的套餐");
        }
        ArrayNode lines = readPackageLines(id);
        if (lines.isEmpty()) throw new IllegalArgumentException("套餐至少需要一项物资");
        validateConsumptionModes(lines);
        String department = repository.text(target, "department");
        String departmentId = resolveDepartmentId(repository.text(target, "departmentId"), department);
        String careType = normalizeCareType(repository.text(target, "careType", repository.text(target, "route")));
        String triggerStage = normalizeTriggerStage(repository.text(target, "triggerStage", "REVIEW"));
        jdbcTemplate.query("SELECT id FROM inventory_packages WHERE department_id = ? AND care_type = ? AND trigger_stage = ? FOR UPDATE",
            (resultSet, rowNumber) -> resultSet.getString("id"), departmentId, careType, triggerStage);
        jdbcTemplate.update(
            "UPDATE inventory_packages SET status = 'disabled', raw_json = JSON_SET(raw_json, '$.status', 'disabled') WHERE department_id = ? AND care_type = ? AND trigger_stage = ? AND status = 'enabled'",
            departmentId, careType, triggerStage
        );
        jdbcTemplate.update(
            "UPDATE inventory_packages SET status = 'enabled', effective_date = COALESCE(NULLIF(effective_date, ''), ?), raw_json = JSON_SET(raw_json, '$.status', 'enabled', '$.effectiveDate', COALESCE(NULLIF(effective_date, ''), ?), '$.operator', ?) WHERE id = ?",
            LocalDate.now().toString(), LocalDate.now().toString(), user.name(), id
        );
        target.put("status", "enabled");
        target.put("operator", user.name());
        return target;
    }

    @Transactional
    public ObjectNode disable(String id, SessionUser user) {
        ObjectNode target = findPackage(id);
        if (target == null) throw new IllegalArgumentException("套餐不存在");
        if (!isManager(user) && !user.department().equals(repository.text(target, "department"))) {
            throw new IllegalArgumentException("不能停用其他科室的套餐");
        }
        jdbcTemplate.update(
            "UPDATE inventory_packages SET status = 'disabled', raw_json = JSON_SET(raw_json, '$.status', 'disabled', '$.operator', ?) WHERE id = ?",
            user.name(), id
        );
        target.put("status", "disabled");
        target.put("operator", user.name());
        return target;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ObjectNode consumeEncounter(
        String encounterId,
        String caseToken,
        String route,
        String department,
        LocalDate visitDate,
        SessionUser user
    ) {
        String normalizedRoute = normalizeCareType(route);
        if (encounterId == null || encounterId.isBlank()) throw new IllegalArgumentException("就诊标识不能为空");
        if (normalizedRoute.isBlank()) throw new IllegalArgumentException("就诊类型必须是门诊或住院");
        if (department == null || department.isBlank()) throw new IllegalArgumentException("科室不能为空");
        ObjectNode previous = findConsumptionEvent(encounterId, normalizedRoute);
        if (previous != null && !"failed".equals(repository.text(previous, "status"))) return previous;
        if (previous != null && !isManager(user)) return previous;
        String eventId = previous == null ? "consume-" + UUID.randomUUID() : repository.text(previous, "id");
        if (previous != null) jdbcTemplate.update("DELETE FROM inventory_consumption_details WHERE event_id = ?", eventId);
        String date = (visitDate == null ? LocalDate.now() : visitDate).toString();
        ObjectNode event = objectMapper.createObjectNode();
        event.put("id", eventId);
        event.put("encounterId", encounterId);
        event.put("caseToken", caseToken == null ? "" : caseToken);
        event.put("route", normalizedRoute);
        event.put("department", department);
        event.put("visitDate", date);
        event.put("operator", user == null ? "system" : user.name());
        event.put("createdAt", repository.now());
        if (previous != null) event.put("previousFailure", repository.text(previous, "errorMessage"));

        ObjectNode pack = findEnabledPackage(department, normalizedRoute, date);
        if (pack == null) {
            return saveEvent(event, "failed", "未找到该科室和就诊类型的启用套餐", "");
        }
        event.put("packageId", repository.text(pack, "id"));
        event.put("packageName", repository.text(pack, "name"));
        ArrayNode lines = readPackageLines(repository.text(pack, "id"));
        try {
            validateConsumptionModes(lines);
        } catch (IllegalArgumentException error) {
            return saveEvent(event, "failed", error.getMessage(), repository.text(pack, "id"));
        }
        Map<String, BigDecimal> required = aggregateLines(lines);
        Map<String, List<ObjectNode>> batches = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : required.entrySet()) {
            List<ObjectNode> available = repository.chooseBatchesForIssue(entry.getKey(), "");
            batches.put(entry.getKey(), available);
            BigDecimal total = available.stream().map(batch -> repository.quantity(batch, "quantity"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(entry.getValue()) < 0) {
                return saveEvent(event, "failed", "物资库存不足：" + repository.itemLabel(entry.getKey()), "");
            }
        }

        ArrayNode details = objectMapper.createArrayNode();
        for (Map.Entry<String, BigDecimal> entry : required.entrySet()) {
            BigDecimal remaining = entry.getValue();
            for (ObjectNode batch : batches.get(entry.getKey())) {
                if (remaining.signum() <= 0) break;
                BigDecimal taken = remaining.min(repository.quantity(batch, "quantity"));
                repository.putQuantity(batch, "quantity", repository.quantity(batch, "quantity").subtract(taken));
                repository.upsertBatch(batch);
                repository.movement("auto_consume", entry.getKey(), repository.text(batch, "id"), taken.negate(), department,
                    user == null ? "system" : user.name(), "套餐自动扣减：" + repository.text(pack, "name"), eventId);
                ObjectNode detail = details.addObject();
                detail.put("id", "consume-line-" + UUID.randomUUID());
                detail.put("eventId", eventId);
                detail.put("itemId", entry.getKey());
                detail.put("batchId", repository.text(batch, "id"));
                repository.putQuantity(detail, "quantity", taken);
                remaining = remaining.subtract(taken);
            }
        }
        event.set("details", details);
        ObjectNode saved = saveEvent(event, "succeeded", "", repository.text(pack, "id"));
        for (JsonNode detailNode : details) {
            ObjectNode detail = (ObjectNode) detailNode;
            jdbcTemplate.update(
                "INSERT INTO inventory_consumption_details (id, event_id, item_id, batch_id, quantity, raw_json) VALUES (?, ?, ?, ?, ?, ?)",
                repository.text(detail, "id"), eventId, repository.text(detail, "itemId"), repository.text(detail, "batchId"),
                repository.quantity(detail, "quantity"), toJson(detail)
            );
        }
        repository.log(event.path("operator").asText(), "自动扣减库存", "consumption", encounterId,
            "套餐“" + repository.text(pack, "name") + "”完成自动扣减，共分配 " + details.size() + " 个批次");
        saved.set("details", details);
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ObjectNode consumeEncounter(String encounterId, String caseToken, String route, String department, String visitDate, SessionUser user) {
        LocalDate parsed = visitDate == null || visitDate.isBlank() ? LocalDate.now() : LocalDate.parse(visitDate.substring(0, 10));
        return consumeEncounter(encounterId, caseToken, route, department, parsed, user);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ObjectNode retryFailedConsumption(String eventId, SessionUser user) {
        ObjectNode event = jdbcTemplate.query(
            "SELECT raw_json FROM inventory_consumption_events WHERE id = ?",
            resultSet -> resultSet.next() ? readObject(resultSet.getString("raw_json")) : null,
            eventId
        );
        if (event == null || !"failed".equals(repository.text(event, "status"))) {
            throw new IllegalArgumentException("仅支持重试失败的消耗事件");
        }
        return consumeEncounter(
            repository.text(event, "encounterId"), repository.text(event, "caseToken"), repository.text(event, "route"),
            repository.text(event, "department"), repository.text(event, "visitDate"), user
        );
    }

    private ObjectNode saveEvent(ObjectNode event, String status, String error, String packageId) {
        event.put("status", status);
        event.put("errorMessage", error);
        if (!packageId.isBlank()) event.put("packageId", packageId);
        jdbcTemplate.update(
            """
            INSERT INTO inventory_consumption_events
              (id, encounter_id, case_token, route, department, visit_date, package_id, status, error_message, operator_name, created_at, raw_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE case_token = VALUES(case_token), department = VALUES(department), visit_date = VALUES(visit_date),
              package_id = VALUES(package_id), status = VALUES(status), error_message = VALUES(error_message),
              operator_name = VALUES(operator_name), created_at = VALUES(created_at), raw_json = VALUES(raw_json)
            """,
            repository.text(event, "id"), repository.text(event, "encounterId"), repository.text(event, "caseToken"), repository.text(event, "route"),
            repository.text(event, "department"), repository.text(event, "visitDate"), repository.text(event, "packageId"), status, error,
            repository.text(event, "operator"), repository.text(event, "createdAt"), toJson(event)
        );
        return event;
    }

    private Map<String, BigDecimal> aggregateLines(ArrayNode lines) {
        Map<String, BigDecimal> required = new LinkedHashMap<>();
        for (JsonNode line : lines) {
            String itemId = repository.text(line, "itemId");
            BigDecimal quantity = repository.quantity(line, "quantity");
            if (itemId.isBlank() || quantity.signum() <= 0) throw new IllegalArgumentException("套餐物资明细无效");
            required.merge(itemId, quantity, BigDecimal::add);
        }
        return required;
    }

    private ArrayNode normalizeLines(JsonNode source) {
        ArrayNode lines = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) return lines;
        for (JsonNode value : source) {
            ObjectNode line = repository.object(value).deepCopy();
            String itemId = repository.text(line, "itemId");
            BigDecimal quantity = repository.quantity(line, "quantity");
            if (itemId.isBlank() || quantity.signum() <= 0) throw new IllegalArgumentException("套餐明细必须填写物资和正数数量");
            String consumptionMode = normalizeConsumptionMode(repository.text(line, "consumptionMode", PER_VISIT));
            if (!isSupportedConsumptionMode(consumptionMode)) {
                throw new IllegalArgumentException("当前阶段仅支持按次（per_visit）扣减，不支持：" + consumptionMode);
            }
            line.put("id", repository.text(line, "id", "pkg-line-" + UUID.randomUUID()));
            repository.putQuantity(line, "quantity", quantity);
            line.put("consumptionMode", PER_VISIT);
            lines.add(line);
        }
        return lines;
    }

    private void validateConsumptionModes(ArrayNode lines) {
        for (JsonNode line : lines) {
            String consumptionMode = normalizeConsumptionMode(repository.text(line, "consumptionMode", PER_VISIT));
            if (!isSupportedConsumptionMode(consumptionMode)) {
                throw new IllegalArgumentException("当前阶段仅支持按次（per_visit）扣减，不支持：" + consumptionMode);
            }
        }
    }

    private ObjectNode findPackage(String id) {
        List<ObjectNode> rows = jdbcTemplate.query("SELECT raw_json FROM inventory_packages WHERE id = ? FOR UPDATE",
            (resultSet, rowNumber) -> readObject(resultSet.getString("raw_json")), id);
        if (rows.isEmpty()) return null;
        ObjectNode row = rows.get(0);
        row.set("lines", readPackageLines(id));
        return row;
    }

    private ObjectNode findEnabledPackage(String department, String careType, String visitDate) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM inventory_packages WHERE department = ? AND care_type = ? AND status = 'enabled' AND (effective_date IS NULL OR effective_date = '' OR effective_date <= ?) ORDER BY version_no DESC, effective_date DESC LIMIT 1 FOR UPDATE",
            (resultSet, rowNumber) -> readObject(resultSet.getString("raw_json")), department, careType, visitDate);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ObjectNode findConsumptionEvent(String encounterId, String route) {
        List<ObjectNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM inventory_consumption_events WHERE encounter_id = ? AND route = ? FOR UPDATE",
            (resultSet, rowNumber) -> readObject(resultSet.getString("raw_json")), encounterId, route);
        if (rows.isEmpty()) return null;
        ObjectNode row = rows.get(0);
        row.set("details", readConsumptionDetails(row.path("id").asText()));
        return row;
    }

    private ArrayNode readPackageLines(String packageId) {
        ArrayNode lines = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT raw_json FROM inventory_package_lines WHERE package_id = ? ORDER BY id ASC",
            resultSet -> { while (resultSet.next()) lines.add(readObject(resultSet.getString("raw_json"))); return null; }, packageId);
        return lines;
    }

    private ArrayNode readConsumptionDetails(String eventId) {
        ArrayNode details = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT raw_json FROM inventory_consumption_details WHERE event_id = ? ORDER BY id ASC",
            resultSet -> { while (resultSet.next()) details.add(readObject(resultSet.getString("raw_json"))); return null; }, eventId);
        return details;
    }

    private int nextVersion(String departmentId, String careType, String triggerStage) {
        Integer version = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version_no), 0) + 1 FROM inventory_packages WHERE department_id = ? AND care_type = ? AND trigger_stage = ?",
            Integer.class, departmentId, careType, triggerStage);
        return version == null ? 1 : version;
    }

    private String resolveDepartmentId(String departmentId, String department) {
        if (departmentId != null && !departmentId.isBlank()) return departmentId;
        List<String> rows = jdbcTemplate.query(
            "SELECT id FROM clinic_departments WHERE name = ? ORDER BY id LIMIT 1",
            (resultSet, rowNumber) -> resultSet.getString(1), department
        );
        if (rows.isEmpty()) throw new IllegalArgumentException("未找到套餐所属科室：" + department);
        return rows.get(0);
    }

    static String normalizeTriggerStage(String stage) {
        if (stage == null || stage.isBlank()) return "REVIEW";
        return stage.trim().toUpperCase();
    }

    private boolean isManager(SessionUser user) {
        return user != null && ("admin".equals(user.role()) || "quality".equals(user.role()));
    }

    static String normalizeCareType(String route) {
        if (route == null) return "";
        return switch (route.trim().toLowerCase()) {
            case "outpatient", "outpatient_visit", "门诊" -> "outpatient";
            case "inpatient", "住院" -> "inpatient";
            default -> "";
        };
    }

    static String normalizeConsumptionMode(String mode) {
        if (mode == null || mode.isBlank()) return PER_VISIT;
        return mode.trim().toLowerCase();
    }

    static boolean isSupportedConsumptionMode(String mode) {
        return PER_VISIT.equals(normalizeConsumptionMode(mode));
    }

    private ObjectNode readObject(String rawJson) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception error) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize inventory package", error);
        }
    }
}
