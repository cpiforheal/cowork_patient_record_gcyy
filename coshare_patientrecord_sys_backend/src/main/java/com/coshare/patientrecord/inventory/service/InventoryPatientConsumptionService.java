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
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Patient-bound consumable workbench. A service instance is the traceable unit:
 * encounter + department + service/stage instance.
 */
@Service
@Profile("mysql")
public class InventoryPatientConsumptionService {

    private static final Set<String> EDITABLE_DOCUMENT_STATUSES = Set.of("DRAFT", "RETURNED", "PARTIALLY_APPROVED");
    private static final Map<String, Department> DEPARTMENTS = departments();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryAccessService inventoryAccess;
    private final InventoryRepository repository;

    public InventoryPatientConsumptionService(
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
    public ObjectNode workbench(String departmentKey, LocalDate businessDate, SessionUser user) {
        Department department = requireDepartment(departmentKey, user);
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        ArrayNode patients = jdbcTemplate.query(
            """
            SELECT si.*, d.id document_id, d.status document_status, d.revision document_revision,
                   d.submitted_at, d.submitted_by,
                   COALESCE((SELECT SUM(l.actual_quantity)
                             FROM inventory_patient_consumption_lines l
                             WHERE l.document_id = d.id), 0) total_quantity,
                   COALESCE((SELECT COUNT(*)
                             FROM inventory_patient_consumption_lines l
                             WHERE l.document_id = d.id), 0) line_count
            FROM inventory_service_instances si
            LEFT JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id
            WHERE si.department_key = ? AND si.business_date = ?
            ORDER BY si.service_started_at, si.patient_name
            """,
            resultSet -> {
                ArrayNode rows = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) rows.add(instanceRow(resultSet));
                return rows;
            },
            department.key(), date
        );
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("departmentKey", department.key());
        result.put("departmentName", department.name());
        result.put("businessDate", date.toString());
        result.put("cutoverDate", cutoverDate());
        result.set("patients", patients);
        result.set("summary", workbenchSummary(patients));
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode document(String serviceInstanceId, SessionUser user) {
        Instance instance = requireInstance(serviceInstanceId, user);
        ObjectNode result = instanceJson(instance);
        Document document = loadDocument(instance.id());
        if (document == null) {
            result.put("documentId", "");
            result.put("status", "NOT_STARTED");
            result.put("revision", 0);
            result.set("lines", JsonNodeFactory.instance.arrayNode());
        } else {
            result.put("documentId", document.id());
            result.put("status", document.status());
            result.put("revision", document.revision());
            result.put("submittedAt", document.submittedAt() == null ? "" : document.submittedAt().toString());
            result.put("submittedBy", document.submittedBy());
            result.set("lines", loadLines(document.id()));
        }
        result.set("presetItems", presetItems(instance.departmentKey(), instance.serviceCode(), instance.serviceName()));
        result.set("availableItems", availableItems());
        return result;
    }

    @Transactional
    public ObjectNode saveDraft(JsonNode payload, SessionUser user) {
        String serviceInstanceId = text(payload, "serviceInstanceId");
        Instance instance = requireInstance(serviceInstanceId, user);
        Document document = loadDocument(instance.id());
        int expectedRevision = Math.max(payload.path("revision").asInt(0), 0);
        if (document != null) {
            requireEditable(document);
            if (document.revision() != expectedRevision) throw staleDocument();
        } else if (expectedRevision != 0) {
            throw staleDocument();
        }
        List<LineDraft> lines = parseLines(payload.path("lines"));
        String documentId = document == null ? "patient-document-" + UUID.randomUUID() : document.id();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("serviceInstanceId", instance.id());
        raw.put("departmentKey", instance.departmentKey());
        raw.put("businessDate", instance.businessDate().toString());
        raw.put("patientId", instance.patientId());
        raw.put("encounterId", instance.encounterId());
        ArrayNode rawLines = raw.putArray("lines");
        lines.forEach(line -> {
            ObjectNode rawLine = rawLines.addObject();
            rawLine.put("itemId", line.itemId());
            rawLine.put("actualQuantity", line.actualQuantity());
            rawLine.put("source", line.source());
        });
        int startLineNo = 1;
        if (document == null) {
            jdbcTemplate.update(
                """
                INSERT INTO inventory_patient_consumption_documents
                  (id, service_instance_id, department_id, department_key, department_name, business_date,
                   patient_id, encounter_id, status, revision, operator_name, operator_username, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?, CAST(? AS JSON))
                """,
                documentId, instance.id(), instance.departmentId(), instance.departmentKey(), instance.departmentName(),
                instance.businessDate(), instance.patientId(), instance.encounterId(), user.name(), user.username(), json(raw)
            );
        } else {
            Set<String> approvedItems = "PARTIALLY_APPROVED".equals(document.status())
                ? new LinkedHashSet<>(jdbcTemplate.queryForList(
                    "SELECT item_id FROM inventory_patient_consumption_lines WHERE document_id = ? AND review_status = 'APPROVED'",
                    String.class,
                    documentId
                ))
                : Set.of();
            for (LineDraft line : lines) {
                if (approvedItems.contains(line.itemId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已审核通过的耗材不能再次编辑：" + line.itemName());
                }
            }
            int updated = jdbcTemplate.update(
                """
                UPDATE inventory_patient_consumption_documents
                SET revision = revision + 1, operator_name = ?, operator_username = ?, raw_json = CAST(? AS JSON)
                WHERE id = ? AND revision = ?
                """,
                user.name(), user.username(), json(raw), documentId, expectedRevision
            );
            if (updated != 1) throw staleDocument();
            if ("PARTIALLY_APPROVED".equals(document.status())) {
                jdbcTemplate.update(
                    "DELETE FROM inventory_patient_consumption_lines WHERE document_id = ? AND review_status <> 'APPROVED'",
                    documentId
                );
                Integer maxLineNo = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(line_no), 0) FROM inventory_patient_consumption_lines WHERE document_id = ?",
                    Integer.class,
                    documentId
                );
                startLineNo = (maxLineNo == null ? 0 : maxLineNo) + 1;
            } else {
                jdbcTemplate.update("DELETE FROM inventory_patient_consumption_lines WHERE document_id = ?", documentId);
            }
        }
        insertLines(documentId, lines, startLineNo);
        jdbcTemplate.update(
            "UPDATE inventory_service_instances SET status = 'DRAFT' WHERE id = ? AND status = 'OPEN'",
            instance.id()
        );
        repository.log(user.name(), "保存患者耗材清单", "patient_consumption_document", instance.patientName() + " " + instance.businessDate(),
            "患者耗材清单保存为草稿，尚未提交审核");
        return document(instance.id(), user);
    }

    @Transactional
    public ObjectNode submit(String documentId, SessionUser user) {
        Document document = requireDocument(documentId, user);
        Instance instance = requireInstance(document.serviceInstanceId(), user);
        if (!EDITABLE_DOCUMENT_STATUSES.contains(document.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该患者耗材清单已提交或已审核，不能重复提交");
        }
        int openLineCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM inventory_patient_consumption_lines
            WHERE document_id = ? AND review_status <> 'APPROVED'
            """,
            Integer.class,
            documentId
        );
        String nextStatus;
        if (openLineCount > 0) {
            jdbcTemplate.update(
                """
                UPDATE inventory_patient_consumption_lines
                SET review_status = 'PENDING', review_note = NULL,
                    line_revision = line_revision + 1
                WHERE document_id = ? AND review_status = 'REJECTED'
                """,
                documentId
            );
            nextStatus = "SUBMITTED";
        } else {
            nextStatus = "APPROVED";
        }
        jdbcTemplate.update(
            """
            UPDATE inventory_patient_consumption_documents
            SET status = ?, submitted_at = CURRENT_TIMESTAMP(3), submitted_by = ?
            WHERE id = ?
            """,
            nextStatus, user.name(), documentId
        );
        jdbcTemplate.update("UPDATE inventory_service_instances SET status = ? WHERE id = ?", nextStatus, instance.id());
        String batchId = upsertBatch(instance);
        refreshBatch(batchId);
        repository.log(user.name(), "提交患者耗材清单", "patient_consumption_document", instance.patientName() + " " + instance.businessDate(),
            "已提交库管审核，审核通过前不会扣减库存");
        return document(instance.id(), user);
    }

    @Transactional
    public ObjectNode manualAdd(JsonNode payload, SessionUser user) {
        Department department = requireDepartment(text(payload, "departmentKey"), user);
        String encounterId = text(payload, "encounterId");
        if (encounterId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择就诊记录");
        PatientSnapshot patient = resolvePatient(text(payload, "patientId"), encounterId);
        String patientName = firstNonBlank(text(payload, "patientName"), patient.name());
        String visitNo = firstNonBlank(text(payload, "visitNo"), patient.visitNo());
        String careType = normalizeCareType(firstNonBlank(text(payload, "careType"), patient.careType()));
        LocalDateTime startedAt = parseDateTime(firstNonBlank(text(payload, "serviceStartedAt"), LocalDateTime.now().toString()));
        LocalDate businessDate = parseDate(firstNonBlank(text(payload, "businessDate"), startedAt.toLocalDate().toString()));
        String serviceCode = firstNonBlank(text(payload, "serviceCode"), "MANUAL");
        String serviceName = firstNonBlank(text(payload, "serviceName"), "手动补录");
        String instanceId = insertInstance(
            "MANUAL", null, encounterId, patient.careEncounterId(), firstNonBlank(text(payload, "patientId"), patient.id()),
            patientName, visitNo, careType, department, serviceCode, serviceName, text(payload, "stageCode"),
            startedAt, businessDate, firstNonBlank(text(payload, "note"), "科室手动补录"), user.name()
        );
        return document(instanceId, user);
    }

    @Transactional
    public ObjectNode enrollClinicalEvent(JsonNode payload, SessionUser user) {
        Department department = requireDepartment(text(payload, "departmentKey"), user);
        String instanceId = enrollClinicalEventInternal(payload, department, user.name());
        return document(instanceId, user);
    }

    /** Internal clinical hook: no inventory-department access check, caller supplies the mapped department. */
    @Transactional
    public ObjectNode enrollClinicalEventInternal(JsonNode payload, String operatorName) {
        String departmentKey = text(payload, "departmentKey");
        Department department = DEPARTMENTS.get(departmentKey);
        if (department == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知科室：" + departmentKey);
        }
        String instanceId = enrollClinicalEventInternal(payload, department, operatorName);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("serviceInstanceId", instanceId);
        return result;
    }

    private String enrollClinicalEventInternal(JsonNode payload, Department department, String operatorName) {
        String encounterId = text(payload, "encounterId");
        if (encounterId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "临床事件缺少就诊标识");
        String sourceEventId = text(payload, "sourceEventId");
        if (sourceEventId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "临床事件缺少来源事件标识");
        PatientSnapshot patient = resolvePatient(text(payload, "patientId"), encounterId);
        String patientName = firstNonBlank(text(payload, "patientName"), patient.name());
        String visitNo = firstNonBlank(text(payload, "visitNo"), patient.visitNo());
        String careType = normalizeCareType(firstNonBlank(text(payload, "careType"), patient.careType()));
        LocalDateTime startedAt = parseDateTime(firstNonBlank(text(payload, "serviceStartedAt"), LocalDateTime.now().toString()));
        LocalDate businessDate = parseDate(firstNonBlank(text(payload, "businessDate"), startedAt.toLocalDate().toString()));
        String serviceCode = firstNonBlank(text(payload, "serviceCode"), text(payload, "stageCode"));
        if (serviceCode.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "临床事件缺少服务或阶段编码");
        String serviceName = firstNonBlank(text(payload, "serviceName"), serviceCode);
        return insertInstance(
            "CLINICAL_EVENT", sourceEventId, encounterId, patient.careEncounterId(),
            firstNonBlank(text(payload, "patientId"), patient.id()), patientName, visitNo, careType, department,
            serviceCode, serviceName, text(payload, "stageCode"), startedAt, businessDate,
            text(payload, "note"), operatorName
        );
    }

    @Transactional(readOnly = true)
    public ObjectNode searchPatients(String keyword, SessionUser user) {
        String search = keyword == null ? "" : keyword.trim();
        String like = "%" + search + "%";
        ArrayNode rows = jdbcTemplate.query(
            """
            SELECT e.id encounter_id,
                   COALESCE(NULLIF(e.source_patient_id, ''), e.patient_case_id, e.id) patient_id,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.patientName')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.patient_json, '$.name')), ''), '') patient_name,
                   e.visit_no, e.inventory_care_type care_type, e.current_stage,
                   e.owning_department_id department_id, COALESCE(d.name, '') department_name,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.visitDate')), ''),
                            LEFT(e.created_at, 10), '') visit_date,
                   e.case_token
            FROM pre_ai_encounters e
            LEFT JOIN pre_ai_patient_cases c ON c.id = e.patient_case_id
            LEFT JOIN clinic_departments d ON d.id = e.owning_department_id
            WHERE e.status <> 'CANCELLED'
              AND (? = '' OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.patientName')), '') LIKE ?
                   OR e.case_token LIKE ? OR CAST(e.visit_no AS CHAR) LIKE ?)
            ORDER BY e.updated_at DESC
            LIMIT 30
            """,
            resultSet -> {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("source", "PRE_AI");
                    row.put("encounterId", resultSet.getString("encounter_id"));
                    row.put("patientId", resultSet.getString("patient_id"));
                    row.put("patientName", resultSet.getString("patient_name"));
                    row.put("visitNo", resultSet.getObject("visit_no") == null ? "" : String.valueOf(resultSet.getObject("visit_no")));
                    row.put("careType", resultSet.getString("care_type"));
                    row.put("currentStage", resultSet.getString("current_stage"));
                    row.put("departmentId", resultSet.getString("department_id"));
                    row.put("departmentName", resultSet.getString("department_name"));
                    row.put("visitDate", resultSet.getString("visit_date"));
                    row.put("caseToken", resultSet.getString("case_token"));
                }
                return list;
            },
            search, like, like, like
        );
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("list", rows);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode trace(String patientId, SessionUser user) {
        if (patientId == null || patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "患者标识不能为空");
        StringBuilder sql = new StringBuilder("SELECT * FROM inventory_service_instances WHERE patient_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(patientId.trim());
        if (!inventoryAccess.canViewAllDepartments(user)) {
            sql.append(" AND department_name = ?");
            params.add(canonicalDepartmentName(user.department()));
        }
        sql.append(" ORDER BY business_date DESC, service_started_at DESC");
        List<ObjectNode> rows = jdbcTemplate.query(
            sql.toString(),
            (resultSet, rowNum) -> instanceRow(resultSet),
            params.toArray()
        );
        ArrayNode instances = JsonNodeFactory.instance.arrayNode();
        for (ObjectNode instance : rows) {
            Document document = loadDocument(instance.path("serviceInstanceId").asText());
            if (document == null) {
                instance.put("documentId", "");
                instance.put("status", "NOT_STARTED");
                instance.set("lines", JsonNodeFactory.instance.arrayNode());
            } else {
                instance.put("documentId", document.id());
                instance.put("status", document.status());
                instance.put("submittedAt", document.submittedAt() == null ? "" : document.submittedAt().toString());
                instance.set("lines", loadLines(document.id()));
            }
            instances.add(instance);
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("patientId", patientId);
        result.put("cutoverDate", cutoverDate());
        result.set("instances", instances);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode runtimeSettings() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("patientDetailCutoverDate", cutoverDate());
        return result;
    }

    @Transactional
    public ObjectNode saveCutoverDate(String date, SessionUser user) {
        if (date == null || date.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择患者耗材切换日期");
        }
        LocalDate parsed = parseDate(date.trim());
        jdbcTemplate.update(
            """
            INSERT INTO inventory_runtime_settings (setting_key, setting_value, updated_by)
            VALUES ('patient_detail_cutover_date', ?, ?)
            ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_by = VALUES(updated_by)
            """,
            parsed.toString(), user.name()
        );
        repository.log(user.name(), "设置患者耗材切换日期", "inventory_runtime_settings", parsed.toString(),
            "切换日前旧日报只读，切换日起患者耗材明细成为统计来源");
        return runtimeSettings();
    }

    @Transactional(readOnly = true)
    public ObjectNode dashboard(LocalDate businessDate, SessionUser user) {
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        String departmentFilter = inventoryAccess.canViewAllDepartments(user) ? "" : canonicalDepartmentName(user.department());
        ObjectNode summary = jdbcTemplate.query(
            """
            SELECT COUNT(DISTINCT si.id) total_patients,
                   COUNT(DISTINCT CASE WHEN d.status IN ('SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED') THEN d.id END) submitted_documents,
                   COUNT(DISTINCT CASE WHEN d.status = 'APPROVED' THEN d.id END) approved_documents,
                   COUNT(DISTINCT CASE WHEN d.status = 'RETURNED' THEN d.id END) returned_documents,
                   COUNT(DISTINCT CASE WHEN d.status = 'DRAFT' THEN d.id END) draft_documents
            FROM inventory_service_instances si
            LEFT JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id
            WHERE si.business_date = ? AND (? = '' OR si.department_name = ?)
            """,
            resultSet -> {
                ObjectNode row = objectMapper.createObjectNode();
                if (resultSet.next()) {
                    row.put("totalPatients", resultSet.getInt("total_patients"));
                    row.put("submittedDocuments", resultSet.getInt("submitted_documents"));
                    row.put("approvedDocuments", resultSet.getInt("approved_documents"));
                    row.put("returnedDocuments", resultSet.getInt("returned_documents"));
                    row.put("draftDocuments", resultSet.getInt("draft_documents"));
                }
                return row;
            },
            date, departmentFilter, departmentFilter
        );
        ObjectNode lineSummary = jdbcTemplate.query(
            """
            SELECT COUNT(l.id) line_count,
                   COALESCE(SUM(CASE WHEN l.review_status = 'APPROVED' THEN 1 ELSE 0 END), 0) approved_lines,
                   COALESCE(SUM(CASE WHEN l.review_status = 'REJECTED' THEN 1 ELSE 0 END), 0) rejected_lines,
                   COALESCE(SUM(CASE WHEN l.review_status = 'PENDING' THEN 1 ELSE 0 END), 0) pending_lines
            FROM inventory_patient_consumption_lines l
            JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            WHERE si.business_date = ? AND (? = '' OR si.department_name = ?)
            """,
            resultSet -> {
                ObjectNode row = objectMapper.createObjectNode();
                if (resultSet.next()) {
                    row.put("lineCount", resultSet.getInt("line_count"));
                    row.put("approvedLines", resultSet.getInt("approved_lines"));
                    row.put("rejectedLines", resultSet.getInt("rejected_lines"));
                    row.put("pendingLines", resultSet.getInt("pending_lines"));
                }
                return row;
            },
            date, departmentFilter, departmentFilter
        );
        lineSummary.fields().forEachRemaining(entry -> summary.set(entry.getKey(), entry.getValue()));
        ArrayNode byDepartment = jdbcTemplate.query(
            """
            SELECT si.department_key, si.department_name,
                   COUNT(DISTINCT si.id) patient_count,
                   COUNT(l.id) line_count,
                   COALESCE(SUM(CASE WHEN l.review_status = 'APPROVED' THEN 1 ELSE 0 END), 0) approved_lines
            FROM inventory_service_instances si
            LEFT JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id
            LEFT JOIN inventory_patient_consumption_lines l ON l.document_id = d.id
            WHERE si.business_date = ? AND (? = '' OR si.department_name = ?)
            GROUP BY si.department_key, si.department_name
            ORDER BY si.department_name
            """,
            resultSet -> {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("departmentKey", resultSet.getString("department_key"));
                    row.put("departmentName", resultSet.getString("department_name"));
                    row.put("patientCount", resultSet.getInt("patient_count"));
                    row.put("lineCount", resultSet.getInt("line_count"));
                    row.put("approvedLines", resultSet.getInt("approved_lines"));
                }
                return list;
            },
            date, departmentFilter, departmentFilter
        );
        ArrayNode topItems = jdbcTemplate.query(
            """
            SELECT i.id item_id, i.name item_name, i.unit,
                   SUM(l.actual_quantity) quantity
            FROM inventory_patient_consumption_lines l
            JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
            JOIN inventory_service_instances si ON si.id = d.service_instance_id
            JOIN inventory_items i ON i.id = l.item_id
            WHERE si.business_date = ? AND l.review_status = 'APPROVED' AND (? = '' OR si.department_name = ?)
            GROUP BY i.id, i.name, i.unit
            ORDER BY quantity DESC
            LIMIT 10
            """,
            resultSet -> {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("itemId", resultSet.getString("item_id"));
                    row.put("itemName", resultSet.getString("item_name"));
                    row.put("unit", resultSet.getString("unit"));
                    row.put("quantity", resultSet.getBigDecimal("quantity"));
                }
                return list;
            },
            date, departmentFilter, departmentFilter
        );
        ObjectNode result = objectMapper.createObjectNode();
        result.put("businessDate", date.toString());
        result.put("cutoverDate", cutoverDate());
        result.set("summary", summary);
        result.set("byDepartment", byDepartment);
        result.set("topItems", topItems);
        return result;
    }

    @Transactional(readOnly = true)
    public ObjectNode presetRules(String departmentKey, SessionUser user) {
        Department department = requireDepartment(departmentKey, user);
        return presetItems(department.key(), "", "");
    }

    private ObjectNode presetItems(String departmentKey, String serviceCode, String serviceName) {
        ArrayNode items = jdbcTemplate.query(
            """
            SELECT r.id rule_id, r.item_id, i.name item_name, i.unit item_unit,
                   r.material_name, r.unit rule_unit, r.standard_quantity, r.service_group,
                   r.care_type, r.binding_type, r.trigger_event, r.common_item
            FROM inventory_quota_rules r
            JOIN inventory_quota_versions v ON v.id = r.version_id AND v.status = 'ACTIVE'
            JOIN inventory_items i ON i.id = r.item_id AND i.enabled = 1
            WHERE r.department_key = ? AND r.enabled = 1
            ORDER BY r.common_item DESC, r.service_group, i.name
            """,
            resultSet -> {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("ruleId", resultSet.getString("rule_id"));
                    row.put("itemId", resultSet.getString("item_id"));
                    row.put("itemName", resultSet.getString("item_name"));
                    row.put("unit", firstNonBlank(resultSet.getString("item_unit"), resultSet.getString("rule_unit")));
                    row.put("materialName", resultSet.getString("material_name"));
                    row.put("presetQuantity", referenceQuantity(resultSet.getBigDecimal("standard_quantity")));
                    row.put("serviceGroup", resultSet.getString("service_group"));
                    row.put("careType", resultSet.getString("care_type"));
                    row.put("bindingType", resultSet.getString("binding_type"));
                    row.put("triggerEvent", resultSet.getString("trigger_event"));
                    row.put("commonItem", resultSet.getBoolean("common_item"));
                    row.put("matchesService",
                        (!serviceCode.isBlank() && serviceCode.equals(resultSet.getString("trigger_event")))
                            || (!serviceName.isBlank() && serviceName.equals(resultSet.getString("service_group"))));
                    list.add(row);
                }
                return list;
            },
            departmentKey
        );
        ObjectNode result = objectMapper.createObjectNode();
        result.set("items", items);
        return result;
    }

    private ArrayNode availableItems() {
        return jdbcTemplate.query(
            """
            SELECT id, name, unit, spec, category
            FROM inventory_items
            WHERE enabled = 1
            ORDER BY name
            LIMIT 500
            """,
            resultSet -> {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("itemId", resultSet.getString("id"));
                    row.put("itemName", resultSet.getString("name"));
                    row.put("unit", resultSet.getString("unit"));
                    row.put("spec", resultSet.getString("spec"));
                    row.put("category", resultSet.getString("category"));
                }
                return list;
            }
        );
    }

    private ArrayNode loadLines(String documentId) {
        return jdbcTemplate.query(
            """
            SELECT l.*, i.name item_name, i.unit item_unit
            FROM inventory_patient_consumption_lines l
            JOIN inventory_items i ON i.id = l.item_id
            WHERE l.document_id = ?
            ORDER BY l.line_no
            """,
            resultSet -> {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                while (resultSet.next()) {
                    ObjectNode row = list.addObject();
                    row.put("lineId", resultSet.getString("id"));
                    row.put("itemId", resultSet.getString("item_id"));
                    row.put("itemName", firstNonBlank(resultSet.getString("item_name"), resultSet.getString("item_name_snapshot")));
                    row.put("unit", firstNonBlank(resultSet.getString("item_unit"), resultSet.getString("unit_snapshot")));
                    row.put("ruleId", resultSet.getString("rule_id"));
                    row.put("presetQuantity", resultSet.getBigDecimal("preset_quantity"));
                    row.put("actualQuantity", resultSet.getBigDecimal("actual_quantity"));
                    row.put("source", resultSet.getString("source"));
                    row.put("exceptionReason", resultSet.getString("exception_reason"));
                    row.put("reviewStatus", resultSet.getString("review_status"));
                    row.put("reviewNote", resultSet.getString("review_note"));
                    row.put("lineRevision", resultSet.getInt("line_revision"));
                    row.put("approvedQuantity", resultSet.getBigDecimal("approved_quantity"));
                    row.put("consumptionEventId", resultSet.getString("consumption_event_id"));
                    list.add(row);
                }
                return list;
            },
            documentId
        );
    }

    private List<LineDraft> parseLines(JsonNode linesNode) {
        if (linesNode == null || linesNode.isNull()) return List.of();
        if (!linesNode.isArray()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材明细格式不正确");
        List<LineDraft> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : linesNode) {
            String itemId = text(node, "itemId");
            if (itemId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材明细缺少物资主数据");
            BigDecimal actual = decimal(node, "actualQuantity");
            if (actual == null || actual.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实际用量必须为非负数");
            if (actual.signum() == 0) continue;
            if (!seen.add(itemId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一患者不能重复添加同一耗材");
            ItemSnapshot item = requireItem(itemId);
            BigDecimal preset = decimal(node, "presetQuantity");
            String source = firstNonBlank(text(node, "source"), "PRESET").toUpperCase(Locale.ROOT);
            String exceptionReason = text(node, "exceptionReason");
            if ("MANUAL".equals(source) && exceptionReason.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "追加预设外耗材时必须填写原因");
            }
            lines.add(new LineDraft(item.id(), item.name(), item.unit(), text(node, "ruleId"), text(node, "ruleVersion"),
                preset, actual, source, exceptionReason));
        }
        return lines;
    }

    private void insertLines(String documentId, List<LineDraft> lines, int startLineNo) {
        int lineNo = Math.max(startLineNo, 1) - 1;
        for (LineDraft line : lines) {
            ObjectNode raw = objectMapper.createObjectNode();
            raw.put("itemId", line.itemId());
            raw.put("actualQuantity", line.actualQuantity());
            raw.put("source", line.source());
            jdbcTemplate.update(
                """
                INSERT INTO inventory_patient_consumption_lines
                  (id, document_id, line_no, item_id, item_name_snapshot, unit_snapshot, rule_id, rule_version,
                   preset_quantity, actual_quantity, source, exception_reason, review_status, line_revision, raw_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, CAST(? AS JSON))
                """,
                "patient-line-" + UUID.randomUUID(), documentId, ++lineNo, line.itemId(), line.itemName(), line.unit(),
                blankToNull(line.ruleId()), blankToNull(line.ruleVersion()), line.presetQuantity(), line.actualQuantity(),
                line.source(), blankToNull(line.exceptionReason()), json(raw)
            );
        }
    }

    private String insertInstance(
        String sourceType,
        String sourceEventId,
        String encounterId,
        String careEncounterId,
        String patientId,
        String patientName,
        String visitNo,
        String careType,
        Department department,
        String serviceCode,
        String serviceName,
        String stageCode,
        LocalDateTime startedAt,
        LocalDate businessDate,
        String note,
        String operatorName
    ) {
        String id = "service-instance-" + UUID.randomUUID();
        String departmentId = resolveDepartmentId(department.key());
        jdbcTemplate.update(
            """
            INSERT INTO inventory_service_instances
              (id, source_type, source_event_id, encounter_id, care_encounter_id, patient_id, patient_name, visit_no,
               care_type, department_id, department_key, department_name, service_code, service_name, stage_code,
               service_started_at, business_date, status, source_note, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """,
            id, sourceType, blankToNull(sourceEventId), encounterId, blankToNull(careEncounterId), patientId, patientName,
            blankToNull(visitNo), careType, departmentId, department.key(), department.name(), serviceCode, serviceName,
            blankToNull(stageCode), Timestamp.valueOf(startedAt), businessDate, blankToNull(note), operatorName
        );
        List<String> existing = jdbcTemplate.query(
            """
            SELECT id FROM inventory_service_instances
            WHERE encounter_id = ? AND department_id = ? AND service_code = ? AND service_started_at = ?
            LIMIT 1
            """,
            (resultSet, rowNum) -> resultSet.getString(1),
            encounterId, departmentId, serviceCode, Timestamp.valueOf(startedAt)
        );
        if (!existing.isEmpty()) return existing.get(0);
        if (sourceEventId != null && !sourceEventId.isBlank()) {
            List<String> byEvent = jdbcTemplate.query(
                "SELECT id FROM inventory_service_instances WHERE source_event_id = ? LIMIT 1",
                (resultSet, rowNum) -> resultSet.getString(1),
                sourceEventId
            );
            if (!byEvent.isEmpty()) return byEvent.get(0);
        }
        return id;
    }

    private String upsertBatch(Instance instance) {
        String batchId = "approval-batch-" + UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO inventory_consumption_approval_batches
              (id, department_id, department_key, department_name, business_date, status, document_count, line_count,
               approved_line_count, rejected_line_count)
            VALUES (?, ?, ?, ?, ?, 'PENDING', 0, 0, 0, 0)
            ON DUPLICATE KEY UPDATE department_name = VALUES(department_name)
            """,
            batchId, instance.departmentId(), instance.departmentKey(), instance.departmentName(), instance.businessDate()
        );
        return jdbcTemplate.queryForObject(
            "SELECT id FROM inventory_consumption_approval_batches WHERE department_id = ? AND business_date = ?",
            String.class,
            instance.departmentId(), instance.businessDate()
        );
    }

    public void refreshBatch(String batchId) {
        jdbcTemplate.update(
            """
            UPDATE inventory_consumption_approval_batches b
            SET document_count = (
                    SELECT COUNT(*) FROM inventory_patient_consumption_documents d
                    WHERE d.department_id = b.department_id AND d.business_date = b.business_date
                ),
                line_count = (
                    SELECT COUNT(*) FROM inventory_patient_consumption_lines l
                    JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
                    WHERE d.department_id = b.department_id AND d.business_date = b.business_date
                ),
                approved_line_count = (
                    SELECT COUNT(*) FROM inventory_patient_consumption_lines l
                    JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
                    WHERE d.department_id = b.department_id AND d.business_date = b.business_date
                      AND l.review_status = 'APPROVED'
                ),
                rejected_line_count = (
                    SELECT COUNT(*) FROM inventory_patient_consumption_lines l
                    JOIN inventory_patient_consumption_documents d ON d.id = l.document_id
                    WHERE d.department_id = b.department_id AND d.business_date = b.business_date
                      AND l.review_status = 'REJECTED'
                )
            WHERE b.id = ?
            """,
            batchId
        );
        jdbcTemplate.update(
            """
            UPDATE inventory_consumption_approval_batches
            SET status = CASE
                    WHEN line_count = approved_line_count + rejected_line_count AND rejected_line_count > 0
                         AND approved_line_count > 0 THEN 'PARTIALLY_APPROVED'
                    WHEN line_count = approved_line_count + rejected_line_count AND rejected_line_count > 0 THEN 'RETURNED'
                    WHEN line_count = approved_line_count THEN 'APPROVED'
                    WHEN approved_line_count + rejected_line_count > 0 THEN 'PARTIALLY_APPROVED'
                    ELSE 'PENDING'
                END
            WHERE id = ?
            """,
            batchId
        );
    }

    private ObjectNode instanceRow(ResultSet resultSet) throws SQLException {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("serviceInstanceId", resultSet.getString("id"));
        row.put("sourceType", resultSet.getString("source_type"));
        row.put("patientId", resultSet.getString("patient_id"));
        row.put("patientName", resultSet.getString("patient_name"));
        row.put("visitNo", resultSet.getString("visit_no"));
        row.put("encounterId", resultSet.getString("encounter_id"));
        row.put("careType", resultSet.getString("care_type"));
        row.put("departmentId", resultSet.getString("department_id"));
        row.put("departmentKey", resultSet.getString("department_key"));
        row.put("departmentName", resultSet.getString("department_name"));
        row.put("serviceCode", resultSet.getString("service_code"));
        row.put("serviceName", resultSet.getString("service_name"));
        row.put("stageCode", resultSet.getString("stage_code"));
        row.put("serviceStartedAt", resultSet.getTimestamp("service_started_at") == null
            ? "" : resultSet.getTimestamp("service_started_at").toLocalDateTime().toString());
        row.put("businessDate", resultSet.getDate("business_date") == null
            ? "" : resultSet.getDate("business_date").toLocalDate().toString());
        boolean hasDocument = hasColumn(resultSet, "document_id");
        String documentId = hasDocument ? resultSet.getString("document_id") : null;
        row.put("documentId", documentId == null ? "" : documentId);
        row.put("status", documentId == null ? "NOT_STARTED" : resultSet.getString("document_status"));
        row.put("revision", documentId == null ? 0 : resultSet.getInt("document_revision"));
        Timestamp submittedAt = hasColumn(resultSet, "submitted_at") ? resultSet.getTimestamp("submitted_at") : null;
        row.put("submittedAt", submittedAt == null ? "" : submittedAt.toLocalDateTime().toString());
        row.put("submittedBy", hasColumn(resultSet, "submitted_by") ? resultSet.getString("submitted_by") : "");
        if (hasColumn(resultSet, "total_quantity")) {
            row.put("totalQuantity", resultSet.getBigDecimal("total_quantity"));
            row.put("lineCount", resultSet.getInt("line_count"));
        }
        return row;
    }

    private ObjectNode instanceJson(Instance instance) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("serviceInstanceId", instance.id());
        row.put("sourceType", instance.sourceType());
        row.put("patientId", instance.patientId());
        row.put("patientName", instance.patientName());
        row.put("visitNo", instance.visitNo());
        row.put("encounterId", instance.encounterId());
        row.put("careType", instance.careType());
        row.put("departmentId", instance.departmentId());
        row.put("departmentKey", instance.departmentKey());
        row.put("departmentName", instance.departmentName());
        row.put("serviceCode", instance.serviceCode());
        row.put("serviceName", instance.serviceName());
        row.put("stageCode", instance.stageCode());
        row.put("serviceStartedAt", instance.serviceStartedAt().toString());
        row.put("businessDate", instance.businessDate().toString());
        return row;
    }

    private ObjectNode workbenchSummary(ArrayNode patients) {
        int total = 0, draft = 0, submitted = 0, approved = 0, returned = 0, notStarted = 0;
        for (JsonNode patient : patients) {
            total++;
            String status = patient.path("status").asText("");
            switch (status) {
                case "DRAFT" -> draft++;
                case "SUBMITTED", "PARTIALLY_APPROVED" -> submitted++;
                case "APPROVED" -> approved++;
                case "RETURNED" -> returned++;
                case "NOT_STARTED" -> notStarted++;
                default -> { }
            }
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("total", total);
        summary.put("draft", draft);
        summary.put("submitted", submitted);
        summary.put("approved", approved);
        summary.put("returned", returned);
        summary.put("notStarted", notStarted);
        return summary;
    }

    private Instance requireInstance(String id, SessionUser user) {
        if (id == null || id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者服务实例");
        List<Instance> rows = jdbcTemplate.query(
            "SELECT * FROM inventory_service_instances WHERE id = ?",
            (resultSet, rowNum) -> new Instance(
                resultSet.getString("id"), resultSet.getString("source_type"), resultSet.getString("source_event_id"),
                resultSet.getString("encounter_id"), resultSet.getString("care_encounter_id"), resultSet.getString("patient_id"),
                resultSet.getString("patient_name"), resultSet.getString("visit_no"), resultSet.getString("care_type"),
                resultSet.getString("department_id"), resultSet.getString("department_key"), resultSet.getString("department_name"),
                resultSet.getString("service_code"), resultSet.getString("service_name"), resultSet.getString("stage_code"),
                resultSet.getTimestamp("service_started_at").toLocalDateTime(),
                resultSet.getDate("business_date").toLocalDate()
            ),
            id
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者服务实例不存在");
        Instance instance = rows.get(0);
        requireDepartment(instance.departmentKey(), user);
        return instance;
    }

    private Document requireDocument(String id, SessionUser user) {
        Document document = loadDocumentById(id);
        if (document == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者耗材清单不存在");
        requireInstance(document.serviceInstanceId(), user);
        return document;
    }

    private Document loadDocument(String serviceInstanceId) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_patient_consumption_documents WHERE service_instance_id = ?",
            resultSet -> resultSet.next() ? documentRow(resultSet) : null,
            serviceInstanceId
        );
    }

    private Document loadDocumentById(String id) {
        return jdbcTemplate.query(
            "SELECT * FROM inventory_patient_consumption_documents WHERE id = ?",
            resultSet -> resultSet.next() ? documentRow(resultSet) : null,
            id
        );
    }

    private Document documentRow(ResultSet resultSet) throws SQLException {
        Timestamp submittedAt = resultSet.getTimestamp("submitted_at");
        return new Document(
            resultSet.getString("id"), resultSet.getString("service_instance_id"), resultSet.getString("department_id"),
            resultSet.getString("department_key"), resultSet.getString("department_name"),
            resultSet.getDate("business_date").toLocalDate(), resultSet.getString("patient_id"),
            resultSet.getString("encounter_id"), resultSet.getString("status"), resultSet.getInt("revision"),
            submittedAt == null ? null : submittedAt.toLocalDateTime(), resultSet.getString("submitted_by")
        );
    }

    private ItemSnapshot requireItem(String itemId) {
        List<ItemSnapshot> rows = jdbcTemplate.query(
            "SELECT id, name, unit FROM inventory_items WHERE id = ? AND enabled = 1",
            (resultSet, rowNum) -> new ItemSnapshot(
                resultSet.getString("id"), resultSet.getString("name"), resultSet.getString("unit")
            ),
            itemId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材不存在或已停用：" + itemId);
        return rows.get(0);
    }

    private PatientSnapshot resolvePatient(String patientId, String encounterId) {
        List<PatientSnapshot> preAi = jdbcTemplate.query(
            """
            SELECT e.id encounter_id,
                   COALESCE(NULLIF(e.source_patient_id, ''), e.patient_case_id, e.id) patient_id,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.patientName')), ''),
                            NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.patient_json, '$.name')), ''), '') patient_name,
                   CAST(e.visit_no AS CHAR) visit_no, e.inventory_care_type care_type,
                   (SELECT ce.id FROM pre_ai_care_encounters ce
                    WHERE ce.clinical_encounter_id = e.id
                    ORDER BY CASE WHEN ce.status = 'ACTIVE' THEN 0 ELSE 1 END, ce.started_at DESC LIMIT 1) care_encounter_id
            FROM pre_ai_encounters e
            LEFT JOIN pre_ai_patient_cases c ON c.id = e.patient_case_id
            WHERE e.id = ? AND e.status <> 'CANCELLED'
            """,
            (resultSet, rowNum) -> new PatientSnapshot(
                resultSet.getString("patient_id"), resultSet.getString("patient_name"), resultSet.getString("encounter_id"),
                firstNonBlank(resultSet.getString("visit_no"), ""), firstNonBlank(resultSet.getString("care_type"), "outpatient"),
                resultSet.getString("care_encounter_id")
            ),
            encounterId
        );
        if (!preAi.isEmpty()) return preAi.get(0);
        List<PatientSnapshot> clinic = jdbcTemplate.query(
            """
            SELECT p.id patient_id, p.name patient_name, e.id encounter_id, e.visit_no, e.visit_type
            FROM clinic_patients p
            JOIN clinic_patient_encounters e ON e.patient_id = p.id
            WHERE e.id = ? AND (? = '' OR p.id = ?)
            """,
            (resultSet, rowNum) -> new PatientSnapshot(
                resultSet.getString("patient_id"), resultSet.getString("patient_name"), resultSet.getString("encounter_id"),
                firstNonBlank(resultSet.getString("visit_no"), ""), normalizeCareType(resultSet.getString("visit_type")), null
            ),
            encounterId, patientId == null ? "" : patientId, patientId == null ? "" : patientId
        );
        if (!clinic.isEmpty()) return clinic.get(0);
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "患者或就诊记录不存在：" + encounterId);
    }

    private Department requireDepartment(String departmentKey, SessionUser user) {
        Department department = DEPARTMENTS.get(departmentKey == null ? "" : departmentKey.trim());
        if (department == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知科室");
        if (!inventoryAccess.canViewAllDepartments(user) && !sameDepartment(user.department(), department.name())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他科室的患者耗材清单");
        }
        return department;
    }

    private String resolveDepartmentId(String departmentKey) {
        List<String> ids = jdbcTemplate.query(
            "SELECT id FROM inventory_departments WHERE department_key = ? AND status = 'ACTIVE' LIMIT 1",
            (resultSet, rowNum) -> resultSet.getString(1),
            departmentKey
        );
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "科室目录未配置：" + departmentKey);
        }
        return ids.get(0);
    }

    private String canonicalDepartmentName(String userDepartment) {
        if (userDepartment == null) return "";
        String value = userDepartment.trim();
        return "后勤保洁".equals(value) ? "后勤" : value;
    }

    private String cutoverDate() {
        List<String> values = jdbcTemplate.query(
            "SELECT setting_value FROM inventory_runtime_settings WHERE setting_key = 'patient_detail_cutover_date'",
            (resultSet, rowNum) -> resultSet.getString(1)
        );
        return values.isEmpty() ? "" : firstNonBlank(values.get(0), "");
    }

    private static boolean sameDepartment(String userDepartment, String canonicalName) {
        if (userDepartment == null) return false;
        String value = userDepartment.trim();
        return value.equals(canonicalName)
            || (value.equals("后勤保洁") && canonicalName.equals("后勤"))
            || (value.equals("后勤") && canonicalName.equals("后勤保洁"));
    }

    private static String normalizeCareType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("inpatient") || normalized.contains("住院")) return "inpatient";
        return "outpatient";
    }

    private static BigDecimal referenceQuantity(BigDecimal value) {
        return value == null ? BigDecimal.ONE : value;
    }

    private static boolean hasColumn(ResultSet resultSet, String column) {
        try {
            resultSet.findColumn(column);
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务日期格式不正确");
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.now();
        String normalized = value.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(normalized).atStartOfDay();
            } catch (DateTimeParseException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务开始时间格式不正确");
            }
        }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) return null;
        try {
            return value.decimalValue();
        } catch (Exception ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须为数字");
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("患者耗材清单序列化失败", error);
        }
    }

    private static ResponseStatusException staleDocument() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "患者耗材清单已被其他人更新，请刷新后再保存");
    }

    private static void requireEditable(Document document) {
        if (!EDITABLE_DOCUMENT_STATUSES.contains(document.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该患者耗材清单已提交或已审核，不能修改");
        }
    }

    private static Map<String, Department> departments() {
        Map<String, Department> result = new LinkedHashMap<>();
        result.put("physiotherapy", new Department("physiotherapy", "理疗室"));
        result.put("laboratory", new Department("laboratory", "检验科"));
        result.put("nursing", new Department("nursing", "护理部"));
        result.put("tcm", new Department("tcm", "中医科"));
        result.put("operating", new Department("operating", "手术室"));
        result.put("anesthesia", new Department("anesthesia", "麻醉室"));
        result.put("endoscopy", new Department("endoscopy", "胃肠镜"));
        result.put("inspection", new Department("inspection", "检查室"));
        result.put("logistics", new Department("logistics", "后勤"));
        result.put("western-pharmacy", new Department("western-pharmacy", "西药房"));
        result.put("cashier", new Department("cashier", "收费室"));
        result.put("tcm-pharmacy", new Department("tcm-pharmacy", "中药房"));
        return Map.copyOf(result);
    }

    private record Department(String key, String name) {}

    private record PatientSnapshot(String id, String name, String encounterId, String visitNo, String careType, String careEncounterId) {}

    private record ItemSnapshot(String id, String name, String unit) {}

    private record LineDraft(
        String itemId, String itemName, String unit, String ruleId, String ruleVersion,
        BigDecimal presetQuantity, BigDecimal actualQuantity, String source, String exceptionReason
    ) {}

    private record Instance(
        String id, String sourceType, String sourceEventId, String encounterId, String careEncounterId,
        String patientId, String patientName, String visitNo, String careType, String departmentId,
        String departmentKey, String departmentName, String serviceCode, String serviceName, String stageCode,
        LocalDateTime serviceStartedAt, LocalDate businessDate
    ) {}

    private record Document(
        String id, String serviceInstanceId, String departmentId, String departmentKey, String departmentName,
        LocalDate businessDate, String patientId, String encounterId, String status, int revision,
        LocalDateTime submittedAt, String submittedBy
    ) {}
}
