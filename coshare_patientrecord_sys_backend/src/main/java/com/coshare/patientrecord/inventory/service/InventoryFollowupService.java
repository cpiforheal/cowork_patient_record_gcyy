package com.coshare.patientrecord.inventory.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Recheck appointments exposed to the inventory follow-up queue. */
@Service
@Profile("mysql")
public class InventoryFollowupService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryPatientConsumptionService consumptionService;

    public InventoryFollowupService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        InventoryPatientConsumptionService consumptionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.consumptionService = consumptionService;
    }

    @Transactional(readOnly = true)
    public ObjectNode appointments(String departmentKey, LocalDate date, SessionUser user) {
        LocalDate businessDate = date == null ? LocalDate.now() : date;
        ArrayNode slots = objectMapper.createArrayNode();
        List<AppointmentRow> rows = jdbcTemplate.query(
            """
            SELECT s.id, s.plan_date, s.time_slot, s.patient_name, s.phone, s.note, s.status,
                   f.encounter_id,
                   COALESCE(e.source_patient_id, e.patient_case_id, '') patient_id,
                   COALESCE(CAST(e.visit_no AS CHAR), '') visit_no,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.patientName')), ''), s.patient_name) encounter_name
            FROM clinic_recheck_schedule s
            LEFT JOIN pre_ai_follow_up_visits f ON f.id = s.source_id
            LEFT JOIN pre_ai_encounters e ON e.id = f.encounter_id
            WHERE s.plan_date = ? AND s.status = 'PLANNED'
            ORDER BY CASE s.time_slot WHEN 'AM' THEN 0 WHEN 'PM' THEN 1 WHEN 'EVENING' THEN 2 ELSE 3 END,
                     s.created_at, s.patient_name
            """,
            (resultSet, rowNum) -> new AppointmentRow(
                resultSet.getString("id"), resultSet.getString("plan_date"), resultSet.getString("time_slot"),
                resultSet.getString("patient_name"), resultSet.getString("phone"), resultSet.getString("note"),
                resultSet.getString("status"), resultSet.getString("encounter_id"),
                resultSet.getString("patient_id"), resultSet.getString("visit_no"), resultSet.getString("encounter_name")
            ),
            businessDate.toString()
        );
        String currentSlot = "";
        ArrayNode currentRows = null;
        for (AppointmentRow row : rows) {
            String slot = slotLabel(row.timeSlot());
            if (!slot.equals(currentSlot)) {
                currentSlot = slot;
                ObjectNode group = slots.addObject();
                group.put("slot", row.timeSlot() == null ? "" : row.timeSlot());
                group.put("label", slot);
                currentRows = group.putArray("items");
            }
            currentRows.add(appointmentNode(row));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("businessDate", businessDate.toString());
        result.put("departmentKey", departmentKey == null ? "" : departmentKey);
        result.set("slots", slots);
        return result;
    }

    @Transactional
    public ObjectNode arrive(String recheckId, JsonNode payload, SessionUser user) {
        LocalDate date = LocalDate.parse(text(payload, "businessDate", LocalDate.now().toString()));
        String departmentKey = text(payload, "departmentKey");
        AppointmentRow appointment = loadAppointment(recheckId);
        if (appointment == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "复查预约不存在");
        MatchedPatient matched = matchPatient(appointment);
        if (matched == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "预约患者尚未匹配，请先搜索确认患者");
        }
        ObjectNode addPayload = objectMapper.createObjectNode();
        addPayload.put("departmentKey", departmentKey);
        addPayload.put("encounterId", matched.encounterId());
        addPayload.put("patientId", matched.patientId());
        addPayload.put("patientName", matched.patientName());
        addPayload.put("visitNo", matched.visitNo());
        addPayload.put("careType", "outpatient");
        addPayload.put("serviceCode", "FOLLOWUP");
        addPayload.put("serviceName", "复诊");
        addPayload.put("sourceType", "REGISTRATION");
        addPayload.put("businessDate", date.toString());
        addPayload.put("serviceStartedAt", date.atTime(8, 0).toString());
        addPayload.put("note", "复查预约到院");
        ObjectNode detail = consumptionService.manualAdd(addPayload, user);
        jdbcTemplate.update(
            "UPDATE clinic_recheck_schedule SET status = 'ARRIVED', status_note = '耗材工作台已到院' WHERE id = ?",
            recheckId
        );
        jdbcTemplate.update(
            "UPDATE inventory_service_instances SET source_recheck_id = ? WHERE id = ?",
            recheckId, detail.path("serviceInstanceId").asText("")
        );
        detail.put("recheckId", recheckId);
        return detail;
    }

    private ObjectNode appointmentNode(AppointmentRow row) {
        MatchedPatient matched = matchPatient(row);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("recheckId", row.id());
        node.put("planDate", row.planDate());
        node.put("timeSlot", row.timeSlot() == null ? "" : row.timeSlot());
        node.put("patientName", row.patientName());
        node.put("phone", row.phone() == null ? "" : row.phone());
        node.put("note", row.note() == null ? "" : row.note());
        node.put("status", row.status());
        if (matched == null) {
            node.put("matchType", "PENDING");
            node.put("matched", false);
            node.put("patientId", "");
            node.put("encounterId", "");
            node.put("visitNo", "");
        } else {
            node.put("matchType", matched.matchType());
            node.put("matched", true);
            node.put("patientId", matched.patientId());
            node.put("encounterId", matched.encounterId());
            node.put("visitNo", matched.visitNo());
            node.put("patientName", matched.patientName());
        }
        return node;
    }

    private MatchedPatient matchPatient(AppointmentRow row) {
        if (row.patientId() != null && !row.patientId().isBlank() && row.encounterId() != null && !row.encounterId().isBlank()) {
            return new MatchedPatient("SOURCE", row.patientId(), row.encounterId(), row.visitNo(),
                row.encounterName() == null ? row.patientName() : row.encounterName());
        }
        if (row.phone() == null || row.phone().isBlank()) return null;
        List<MatchedPatient> matches = jdbcTemplate.query(
            """
            SELECT p.id patient_id, COALESCE(e.id, '') encounter_id, COALESCE(CAST(e.visit_no AS CHAR), p.visit_no, '') visit_no,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.patientName')), ''), p.name) patient_name
            FROM clinic_patients p
            LEFT JOIN pre_ai_encounters e ON e.id = (
              SELECT e2.id FROM pre_ai_encounters e2 WHERE e2.source_patient_id = p.id
              ORDER BY e2.updated_at DESC LIMIT 1
            )
            WHERE p.name = ? AND JSON_UNQUOTE(JSON_EXTRACT(p.raw_json, '$.phone')) = ?
            LIMIT 2
            """,
            (resultSet, rowNum) -> new MatchedPatient("NAME_PHONE", resultSet.getString("patient_id"),
                resultSet.getString("encounter_id"), resultSet.getString("visit_no"), resultSet.getString("patient_name")),
            row.patientName(), row.phone()
        );
        return matches.size() == 1 && !matches.get(0).encounterId().isBlank() ? matches.get(0) : null;
    }

    private AppointmentRow loadAppointment(String recheckId) {
        List<AppointmentRow> rows = jdbcTemplate.query(
            """
            SELECT s.id, s.plan_date, s.time_slot, s.patient_name, s.phone, s.note, s.status,
                   f.encounter_id,
                   COALESCE(e.source_patient_id, e.patient_case_id, '') patient_id,
                   COALESCE(CAST(e.visit_no AS CHAR), '') visit_no,
                   COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.patientName')), ''), s.patient_name) encounter_name
            FROM clinic_recheck_schedule s
            LEFT JOIN pre_ai_follow_up_visits f ON f.id = s.source_id
            LEFT JOIN pre_ai_encounters e ON e.id = f.encounter_id
            WHERE s.id = ?
            """,
            (resultSet, rowNum) -> new AppointmentRow(
                resultSet.getString("id"), resultSet.getString("plan_date"), resultSet.getString("time_slot"),
                resultSet.getString("patient_name"), resultSet.getString("phone"), resultSet.getString("note"),
                resultSet.getString("status"), resultSet.getString("encounter_id"),
                resultSet.getString("patient_id"), resultSet.getString("visit_no"), resultSet.getString("encounter_name")
            ),
            recheckId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String slotLabel(String slot) {
        if ("AM".equals(slot)) return "上午";
        if ("PM".equals(slot)) return "下午";
        if ("EVENING".equals(slot)) return "晚上";
        return "未分时段";
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node == null ? "" : node.path(field).asText("").trim();
        return value.isBlank() ? fallback : value;
    }

    private record AppointmentRow(String id, String planDate, String timeSlot, String patientName, String phone,
                                  String note, String status, String encounterId, String patientId, String visitNo,
                                  String encounterName) {}

    private record MatchedPatient(String matchType, String patientId, String encounterId, String visitNo, String patientName) {}
}
