package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.InventoryAccessService;
import com.coshare.patientrecord.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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

/** Stores patient-linked service consumption drafts without producing inventory movements. */
@Service
@Profile("mysql")
public class InventoryPatientConsumptionDraftService {

    private static final Map<String, String> DEPARTMENTS = departments();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryAccessService inventoryAccess;
    private final InventoryRepository repository;

    public InventoryPatientConsumptionDraftService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryAccessService inventoryAccess,
        InventoryRepository repository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.inventoryAccess = inventoryAccess;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ObjectNode read(String id, SessionUser user) {
        ObjectNode result = jdbcTemplate.query(
            "SELECT * FROM inventory_patient_consumption_drafts WHERE id = ?",
            rows -> rows.next() ? row(rows) : null,
            id
        );
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者耗用草稿不存在");
        requireDepartment(result.path("departmentKey").asText(), user);
        result.set("lines", loadLines(id));
        result.put("exists", true);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode list(String departmentKey, LocalDate businessDate, String patientId, SessionUser user) {
        String requiredDepartment = departmentKey == null ? "" : departmentKey.trim();
        String departmentName = requiredDepartment.isBlank() ? "" : requireDepartment(requiredDepartment, user);
        StringBuilder sql = new StringBuilder("SELECT * FROM inventory_patient_consumption_drafts WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (!departmentName.isBlank()) {
            sql.append(" AND department_key = ?");
            params.add(requiredDepartment);
        } else if (!inventoryAccess.canViewAllDepartments(user)) {
            sql.append(" AND department_name = ?");
            params.add(user.department());
        }
        if (businessDate != null) {
            sql.append(" AND business_date = ?");
            params.add(businessDate);
        }
        if (patientId != null && !patientId.isBlank()) {
            sql.append(" AND patient_id = ?");
            params.add(patientId.trim());
        }
        sql.append(" ORDER BY service_at DESC, updated_at DESC LIMIT 500");
        ArrayNode rows = jdbcTemplate.query(sql.toString(), resultSet -> {
            ArrayNode list = JsonNodeFactory.instance.arrayNode();
            while (resultSet.next()) list.add(row(resultSet));
            return list;
        }, params.toArray());
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("list", rows);
        return result;
    }

    @Transactional
    public ObjectNode save(JsonNode payload, SessionUser user) {
        String id = text(payload, "id");
        String departmentKey = text(payload, "departmentKey");
        String departmentName = requireDepartment(departmentKey, user);
        LocalDate businessDate = parseDate(text(payload, "businessDate"));
        String serviceAt = text(payload, "serviceAt");
        if (serviceAt.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实际服务时间不能为空");
        ArrayNode serviceItems = array(payload, "serviceItems", 1, 40, "服务项目");
        ArrayNode lines = array(payload, "lines", 1, 400, "耗材明细");
        String patientId = text(payload, "patientId");
        String encounterId = text(payload, "encounterId");
        PatientSnapshot patient = requirePatientEncounter(patientId, encounterId);
        validateLines(lines);
        int expectedRevision = Math.max(payload.path("revision").asInt(0), 0);
        String templateVersion = text(payload, "templateVersion");
        if (templateVersion.isBlank()) templateVersion = "department-template-v1";

        if (id.isBlank()) id = "inv-patient-draft-" + UUID.randomUUID();
        DraftVersion current = jdbcTemplate.query(
            "SELECT id, revision, department_key FROM inventory_patient_consumption_drafts WHERE id = ? FOR UPDATE",
            rows -> rows.next() ? new DraftVersion(rows.getString("id"), rows.getInt("revision"), rows.getString("department_key")) : null,
            id
        );
        if (current == null && expectedRevision != 0) throw staleDraft();
        if (current != null) {
            requireDepartment(current.departmentKey(), user);
            if (current.revision() != expectedRevision) throw staleDraft();
        }

        ObjectNode stored = JsonNodeFactory.instance.objectNode();
        stored.put("serviceAt", serviceAt);
        stored.put("templateVersion", templateVersion);
        stored.put("patientName", patient.name());
        stored.put("visitNo", patient.visitNo());
        ObjectNode patientSnapshot = stored.putObject("patientSnapshot");
        patientSnapshot.put("id", patient.id());
        patientSnapshot.put("name", patient.name());
        patientSnapshot.put("visitNo", patient.visitNo());
        ObjectNode encounterSnapshot = stored.putObject("encounterSnapshot");
        encounterSnapshot.put("id", patient.encounterId());
        encounterSnapshot.put("visitNo", patient.visitNo());
        encounterSnapshot.put("visitDate", patient.visitDate());
        encounterSnapshot.put("visitType", patient.visitType());
        encounterSnapshot.put("doctor", patient.doctor());
        stored.set("serviceItems", serviceItems);
        stored.set("lines", lines);

        if (current == null) {
            jdbcTemplate.update(
                "INSERT INTO inventory_patient_consumption_drafts "
                    + "(id, department_key, department_name, patient_id, encounter_id, patient_name, visit_no, business_date, service_at, template_version, revision, operator_name, operator_username, raw_json) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, CAST(? AS JSON))",
                id, departmentKey, departmentName, patient.id(), patient.encounterId(), patient.name(), patient.visitNo(), businessDate, serviceAt,
                templateVersion, user.name(), user.username(), json(stored)
            );
        } else {
            jdbcTemplate.update(
                "UPDATE inventory_patient_consumption_drafts SET department_key = ?, department_name = ?, patient_id = ?, encounter_id = ?, patient_name = ?, visit_no = ?, "
                    + "business_date = ?, service_at = ?, template_version = ?, revision = revision + 1, operator_name = ?, operator_username = ?, raw_json = CAST(? AS JSON) WHERE id = ?",
                departmentKey, departmentName, patient.id(), patient.encounterId(), patient.name(), patient.visitNo(), businessDate, serviceAt,
                templateVersion, user.name(), user.username(), json(stored), id
            );
            jdbcTemplate.update("DELETE FROM inventory_patient_consumption_draft_lines WHERE draft_id = ?", id);
        }
        saveLines(id, lines);
        repository.log(user.name(), "保存患者耗用草稿", "patient_consumption_draft", patient.name() + " " + businessDate, "仅保存患者服务耗材草稿，未生成库存流水");
        return read(id, user);
    }

    @Transactional(readOnly = true)
    public byte[] exportDetails(String departmentKey, LocalDate businessDate, SessionUser user) {
        List<String[]> rows = new ArrayList<>();
        List<PatientExportLine> lines = queryExportLines(departmentKey, businessDate, user);
        addExportMetadata(rows, "患者耗用明细", departmentKey, businessDate, lines.size(), lines.stream().map(PatientExportLine::draftId).distinct().count(), user);
        rows.add(new String[] { "草稿ID", "科室", "业务日期", "服务时间", "患者ID", "患者", "就诊记录ID", "就诊号", "服务项目", "耗材", "单位", "模板定额", "实际数量", "例外原因", "操作账号", "责任人" });
        lines.forEach(line -> rows.add(new String[] {
            line.draftId(), line.departmentName(), line.businessDate(), line.serviceAt(), line.patientId(), line.patientName(),
            line.encounterId(), line.visitNo(), line.serviceItemName(), line.materialName(), line.unit(), quantityText(line.standardQuantity()),
            quantityText(line.actualQuantity()), line.exceptionReason(), line.operatorUsername(), line.operatorName()
        }));
        return csv(rows);
    }

    @Transactional(readOnly = true)
    public byte[] export(String kind, String departmentKey, LocalDate businessDate, SessionUser user) {
        return "summary".equals(kind)
            ? exportSummary(departmentKey, businessDate, user)
            : exportDetails(departmentKey, businessDate, user);
    }

    @Transactional(readOnly = true)
    public byte[] exportSummary(String departmentKey, LocalDate businessDate, SessionUser user) {
        Map<String, SummaryLine> summary = new LinkedHashMap<>();
        List<PatientExportLine> lines = queryExportLines(departmentKey, businessDate, user);
        lines.forEach(line -> {
            String unit = line.unit().trim();
            String material = line.materialName().trim();
            if (unit.isBlank() || material.isBlank()) return;
            String key = line.departmentName() + "\u0000" + material + "\u0000" + unit;
            SummaryLine current = summary.get(key);
            if (current == null) current = new SummaryLine(line.departmentName(), material, unit, 0, 0);
            summary.put(key, new SummaryLine(current.departmentName(), current.materialName(), current.unit(),
                current.actualQuantity() + line.actualQuantity(), current.serviceCount() + 1));
        });
        List<String[]> rows = new ArrayList<>();
        addExportMetadata(rows, "患者耗用科室汇总", departmentKey, businessDate, lines.size(), lines.stream().map(PatientExportLine::draftId).distinct().count(), user);
        rows.add(new String[] { "科室", "耗材", "单位", "实际用量", "耗材明细行数", "业务日期" });
        summary.values().forEach(line -> rows.add(new String[] {
            line.departmentName(), line.materialName(), line.unit(), quantityText(line.actualQuantity()), String.valueOf(line.serviceCount()), businessDate == null ? "" : businessDate.toString()
        }));
        return csv(rows);
    }

    private List<PatientExportLine> queryExportLines(String departmentKey, LocalDate businessDate, SessionUser user) {
        String requiredDepartment = departmentKey == null ? "" : departmentKey.trim();
        String departmentName = requiredDepartment.isBlank() ? "" : requireDepartment(requiredDepartment, user);
        StringBuilder sql = new StringBuilder(
            "SELECT d.id draft_id, d.department_name, d.patient_id, d.patient_name, d.encounter_id, d.visit_no, "
                + "d.business_date, d.service_at, d.operator_name, d.operator_username, "
                + "l.service_item_name, l.material_name, l.unit, l.standard_quantity, l.actual_quantity, l.exception_reason "
                + "FROM inventory_patient_consumption_drafts d "
                + "JOIN inventory_patient_consumption_draft_lines l ON l.draft_id = d.id WHERE 1 = 1"
        );
        List<Object> params = new ArrayList<>();
        if (!departmentName.isBlank()) {
            sql.append(" AND d.department_key = ?");
            params.add(requiredDepartment);
        } else if (!inventoryAccess.canViewAllDepartments(user)) {
            sql.append(" AND d.department_name = ?");
            params.add(user.department());
        }
        if (businessDate != null) {
            sql.append(" AND d.business_date = ?");
            params.add(businessDate);
        }
        sql.append(" ORDER BY d.service_at DESC, d.updated_at DESC, l.line_no");
        return jdbcTemplate.query(sql.toString(), (resultSet, rowNum) -> new PatientExportLine(
            resultSet.getString("draft_id"), resultSet.getString("department_name"), resultSet.getDate("business_date").toLocalDate().toString(),
            resultSet.getString("service_at"), resultSet.getString("patient_id"), resultSet.getString("patient_name"),
            resultSet.getString("encounter_id"), resultSet.getString("visit_no"), resultSet.getString("service_item_name"),
            resultSet.getString("material_name"), resultSet.getString("unit"), nullableDouble(resultSet, "standard_quantity"),
            resultSet.getDouble("actual_quantity"), resultSet.getString("exception_reason"), resultSet.getString("operator_username"),
            resultSet.getString("operator_name")
        ), params.toArray());
    }

    private ArrayNode loadLines(String draftId) {
        ArrayNode lines = JsonNodeFactory.instance.arrayNode();
        jdbcTemplate.query(
            "SELECT id, service_item_id, service_item_name, material_name, unit, standard_quantity, actual_quantity, exception_reason "
                + "FROM inventory_patient_consumption_draft_lines WHERE draft_id = ? ORDER BY line_no",
            resultSet -> {
                while (resultSet.next()) {
                    ObjectNode line = JsonNodeFactory.instance.objectNode();
                    line.put("id", resultSet.getString("id"));
                    line.put("serviceItemId", resultSet.getString("service_item_id"));
                    line.put("serviceItemName", resultSet.getString("service_item_name"));
                    line.put("materialName", resultSet.getString("material_name"));
                    line.put("unit", resultSet.getString("unit"));
                    Double standard = nullableDouble(resultSet, "standard_quantity");
                    if (standard == null) line.putNull("standardQuantity"); else line.put("standardQuantity", standard);
                    line.put("actualQuantity", resultSet.getDouble("actual_quantity"));
                    line.put("exceptionReason", resultSet.getString("exception_reason"));
                    lines.add(line);
                }
                return null;
            },
            draftId
        );
        return lines;
    }

    private static Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private void addExportMetadata(List<String[]> rows, String exportName, String departmentKey, LocalDate businessDate, int lineCount, long draftCount, SessionUser user) {
        rows.add(new String[] { "导出类型", exportName });
        rows.add(new String[] { "科室编码", departmentKey == null ? "" : departmentKey });
        rows.add(new String[] { "业务日期", businessDate == null ? "" : businessDate.toString() });
        rows.add(new String[] { "导出时间", OffsetDateTime.now(ZoneId.systemDefault()).toString() });
        rows.add(new String[] { "操作账号", user.username() });
        rows.add(new String[] { "责任人", user.name() });
        rows.add(new String[] { "草稿数量", String.valueOf(draftCount) });
        rows.add(new String[] { "草稿明细行数", String.valueOf(lineCount) });
        rows.add(new String[] {});
    }

    private void saveLines(String draftId, ArrayNode lines) {
        int lineNo = 0;
        for (JsonNode line : lines) {
            jdbcTemplate.update(
                "INSERT INTO inventory_patient_consumption_draft_lines "
                    + "(id, draft_id, line_no, service_item_id, service_item_name, material_name, unit, standard_quantity, actual_quantity, exception_reason, raw_json) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))",
                "inv-patient-draft-line-" + UUID.randomUUID(), draftId, ++lineNo, text(line, "serviceItemId"), text(line, "serviceItemName"),
                text(line, "materialName"), text(line, "unit"), line.path("standardQuantity").isNull() ? null : line.path("standardQuantity").asDouble(),
                line.path("actualQuantity").asDouble(), text(line, "exceptionReason"), json(line)
            );
        }
    }

    private void validateLines(ArrayNode lines) {
        for (JsonNode line : lines) {
            if (text(line, "serviceItemId").isBlank() || text(line, "materialName").isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材明细缺少服务项目或耗材名称");
            }
            double actualQuantity = line.path("actualQuantity").asDouble(Double.NaN);
            if (!Double.isFinite(actualQuantity) || actualQuantity < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实际耗用数量必须为非负数");
            }
        }
    }

    private PatientSnapshot requirePatientEncounter(String patientId, String encounterId) {
        if (patientId.isBlank() || encounterId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "患者及具体就诊记录不能为空");
        }
        PatientSnapshot patient = jdbcTemplate.query(
            "SELECT p.id patient_id, p.name patient_name, e.id encounter_id, e.visit_no, e.visit_date, e.visit_type, e.doctor "
                + "FROM clinic_patients p JOIN clinic_patient_encounters e ON e.patient_id = p.id WHERE p.id = ? AND e.id = ?",
            rows -> rows.next()
                ? new PatientSnapshot(rows.getString("patient_id"), rows.getString("patient_name"), rows.getString("encounter_id"),
                    rows.getString("visit_no"), rows.getString("visit_date"), rows.getString("visit_type"), rows.getString("doctor"))
                : null,
            patientId, encounterId
        );
        if (patient == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "患者或就诊记录不存在，无法保存草稿");
        return patient;
    }

    private ObjectNode row(ResultSet resultSet) throws SQLException {
        ObjectNode result = readJson(resultSet.getString("raw_json"));
        result.put("id", resultSet.getString("id"));
        result.put("departmentKey", resultSet.getString("department_key"));
        result.put("departmentName", resultSet.getString("department_name"));
        result.put("patientId", resultSet.getString("patient_id"));
        result.put("encounterId", resultSet.getString("encounter_id"));
        result.put("patientName", resultSet.getString("patient_name"));
        result.put("visitNo", resultSet.getString("visit_no"));
        result.put("businessDate", resultSet.getDate("business_date").toLocalDate().toString());
        result.put("serviceAt", resultSet.getString("service_at"));
        result.put("templateVersion", resultSet.getString("template_version"));
        result.put("revision", resultSet.getInt("revision"));
        result.put("operator", resultSet.getString("operator_name"));
        result.put("operatorUsername", resultSet.getString("operator_username"));
        result.put("updatedAt", resultSet.getTimestamp("updated_at").toLocalDateTime().toString());
        return result;
    }

    private ArrayNode array(JsonNode payload, String field, int min, int max, String label) {
        JsonNode value = payload == null ? null : payload.path(field);
        if (!value.isArray() || value.size() < min || value.size() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "格式不正确");
        }
        return (ArrayNode) value;
    }

    private String requireDepartment(String departmentKey, SessionUser user) {
        String departmentName = DEPARTMENTS.get(departmentKey == null ? "" : departmentKey.trim());
        if (departmentName == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知科室核算模板");
        if (!inventoryAccess.canViewAllDepartments(user) && !departmentName.equals(user.department())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他科室的患者耗用草稿");
        }
        return departmentName;
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务日期格式不正确");
        }
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("患者耗用草稿序列化失败", error);
        }
    }

    private ObjectNode readJson(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed != null && parsed.isObject() ? (ObjectNode) parsed : JsonNodeFactory.instance.objectNode();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("患者耗用草稿数据损坏", error);
        }
    }

    private static String quantityText(JsonNode value) {
        return value == null || value.isNull() ? "" : quantityText(value.asDouble());
    }

    private static String quantityText(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static byte[] csv(List<String[]> rows) {
        StringBuilder result = new StringBuilder("\uFEFF");
        for (String[] row : rows) {
            for (int index = 0; index < row.length; index++) {
                if (index > 0) result.append(',');
                result.append('"').append((row[index] == null ? "" : row[index]).replace("\"", "\"\"")).append('"');
            }
            result.append("\r\n");
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static ResponseStatusException staleDraft() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "草稿已被其他人更新，请刷新后再保存");
    }

    private static Map<String, String> departments() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("physiotherapy", "理疗室"); result.put("laboratory", "检验科"); result.put("nursing", "护理部"); result.put("tcm", "中医科");
        result.put("operating", "手术室"); result.put("anesthesia", "麻醉室"); result.put("endoscopy", "胃肠镜"); result.put("inspection", "检查室");
        result.put("logistics", "后勤保洁"); result.put("western-pharmacy", "西药房"); result.put("cashier", "收费室"); result.put("tcm-pharmacy", "中药房");
        return Map.copyOf(result);
    }

    private record DraftVersion(String id, int revision, String departmentKey) {}
    private record PatientSnapshot(String id, String name, String encounterId, String visitNo, String visitDate, String visitType, String doctor) {}
    private record PatientExportLine(
        String draftId, String departmentName, String businessDate, String serviceAt, String patientId, String patientName,
        String encounterId, String visitNo, String serviceItemName, String materialName, String unit, Double standardQuantity,
        double actualQuantity, String exceptionReason, String operatorUsername, String operatorName
    ) {}
    private record SummaryLine(String departmentName, String materialName, String unit, double actualQuantity, int serviceCount) {}
}
