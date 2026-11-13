package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class LabReportPublishService {

    private static final Set<String> PUBLISH_ROLES = Set.of("admin", "doctor");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${clinic.lab-portal.enabled:false}")
    private boolean enabled;

    @Value("${clinic.lab-portal.shared-secret:}")
    private String sharedSecret;

    public LabReportPublishService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> syncEncounter(String encounterId, SessionUser user) {
        if (user != null) requirePublisher(user);
        return syncEncounterInternal(encounterId);
    }

    @Transactional
    public Map<String, Object> syncEncounterInternal(String encounterId) {
        if (!enabled) return status(encounterId);
        Map<String, Object> encounter = loadEncounter(encounterId);
        if (encounter == null) return status(encounterId);
        String encounterStatus = text(encounter.get("status"));
        if (Set.of("CANCELLED", "WITHDRAWN").contains(encounterStatus)) return removeEncounter(encounterId);

        List<String> labStatuses = jdbcTemplate.queryForList(
            "SELECT status FROM pre_ai_auxiliary_tasks WHERE encounter_id = ? AND task_type = 'LAB' ORDER BY updated_at DESC LIMIT 1",
            String.class, encounterId
        );
        String labStatus = labStatuses.isEmpty() ? "DRAFT" : labStatuses.get(0);
        if (!"COMPLETED".equals(labStatus)) {
            Map<String, Object> previous = syncRow(encounterId);
            if (previous != null && !blank(text(previous.get("identity_key")))
                && Set.of("SENT", "PENDING", "FAILED").contains(text(previous.get("push_status")))) {
                return removeEncounter(encounterId);
            }
            return updateSync(encounterId, "", "", null, 0, "WAITING");
        }

        JsonNode patient = readJson(text(encounter.get("patient_json")));
        String name = text(patient.path("patientName"));
        String identityNumber = text(patient.path("identityNumber"));
        String phone = text(patient.path("phone"));
        String identityType;
        String suffix;
        if (identityNumber.length() >= 6) {
            identityType = "ID";
            suffix = identityNumber.substring(identityNumber.length() - 6);
        } else if (phone.length() >= 4) {
            identityType = "PHONE";
            suffix = phone.substring(phone.length() - 4);
        } else {
            Map<String, Object> previous = syncRow(encounterId);
            if (previous != null && !blank(text(previous.get("identity_key")))) return removeEncounter(encounterId);
            return updateSync(encounterId, "", "", null, 0, "NO_IDENTITY");
        }
        if (name.isBlank()) return updateSync(encounterId, "", "", null, 0, "NO_IDENTITY");
        String identityKey = identityKey(identityType, name, suffix);
        List<Map<String, Object>> reports = jdbcTemplate.queryForList("""
            SELECT id, template_name, report_date, metrics_json, version, status
            FROM pre_ai_lab_reports
            WHERE encounter_id = ? AND status = 'ACTIVE'
            ORDER BY report_date DESC, version DESC
            """, encounterId);
        if (reports.isEmpty()) return removeEncounter(encounterId);
        ArrayNode reportNodes = objectMapper.createArrayNode();
        int criticalCount = 0;
        for (Map<String, Object> report : reports) {
            ObjectNode reportNode = reportNodes.addObject();
            reportNode.put("reportId", text(report.get("id")));
            reportNode.put("reportDate", text(report.get("report_date")));
            reportNode.put("templateName", text(report.get("template_name")));
            reportNode.put("version", number(report.get("version"), 1));
            ArrayNode metrics = publicMetrics(text(report.get("metrics_json")));
            int reportCritical = 0;
            int reportAbnormal = 0;
            for (JsonNode metric : metrics) {
                if (metric.path("critical").asBoolean(false)) {
                    reportCritical++;
                    reportAbnormal++;
                } else if ("ABNORMAL".equals(text(metric.path("severity")))) {
                    reportAbnormal++;
                }
            }
            criticalCount += reportCritical;
            reportNode.put("criticalCount", reportCritical);
            reportNode.put("abnormalCount", reportAbnormal);
            reportNode.set("metrics", metrics);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "SNAPSHOT");
        payload.put("identityKey", identityKey);
        payload.put("identityType", identityType);
        ObjectNode patientNode = payload.putObject("patient");
        patientNode.put("name", name);
        patientNode.put("gender", text(patient.path("gender")));
        patientNode.put("age", text(patient.path("age")));
        patientNode.put("visitNo", number(encounter.get("visit_no"), 1));
        patientNode.put("caseTokenMasked", maskToken(text(encounter.get("case_token"))));
        ObjectNode visitNode = payload.putObject("encounter");
        visitNode.put("encounterId", encounterId);
        visitNode.put("visitDate", datePart(text(patient.path("visitDate")), text(encounter.get("created_at"))));
        payload.set("reports", reportNodes);
        payload.put("criticalCount", criticalCount);
        return updateSync(encounterId, identityKey, identityType, toJson(payload), reports.size(), "PENDING");
    }

    @Transactional
    public Map<String, Object> removeEncounter(String encounterId) {
        Map<String, Object> current = syncRow(encounterId);
        if (current == null) return Map.of("encounterId", encounterId, "status", "REMOVED");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "REMOVE");
        payload.put("identityKey", text(current.get("identity_key")));
        payload.put("encounterId", encounterId);
        return updateSync(encounterId, text(current.get("identity_key")), text(current.get("identity_type")), toJson(payload), 0, "PENDING");
    }

    public Map<String, Object> status(String encounterId, SessionUser user) {
        requirePublisher(user);
        return status(encounterId);
    }

    public Map<String, Object> status(String encounterId) {
        Map<String, Object> row = syncRow(encounterId);
        if (row == null) return Map.of("encounterId", encounterId, "status", "NOT_SYNCED", "reportCount", 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encounterId", encounterId);
        result.put("identityType", text(row.get("identity_type")));
        result.put("status", text(row.get("push_status")));
        result.put("reportCount", number(row.get("report_count"), 0));
        result.put("attempts", number(row.get("attempts"), 0));
        result.put("lastError", text(row.get("last_error")));
        result.put("updatedAt", text(row.get("updated_at")));
        return result;
    }

    @Transactional
    public Map<String, Object> revoke(String encounterId, SessionUser user) {
        requirePublisher(user);
        return removeEncounter(encounterId);
    }

    private Map<String, Object> updateSync(String encounterId, String identityKey, String identityType, String payload, int reportCount, String status) {
        jdbcTemplate.update("""
            INSERT INTO clinic_lab_portal_sync
              (encounter_id, identity_key, identity_type, payload_json, report_count, push_status, attempts, next_attempt_at, last_error)
            VALUES (?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP(3), NULL)
            ON DUPLICATE KEY UPDATE
              identity_key = VALUES(identity_key), identity_type = VALUES(identity_type), payload_json = VALUES(payload_json),
              report_count = VALUES(report_count), push_status = VALUES(push_status), attempts = 0,
              next_attempt_at = CURRENT_TIMESTAMP(3), last_error = NULL, updated_at = CURRENT_TIMESTAMP(3)
            """, encounterId, blank(identityKey) ? null : identityKey, blank(identityType) ? null : identityType,
            payload, reportCount, status);
        return status(encounterId);
    }

    private Map<String, Object> syncRow(String encounterId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM clinic_lab_portal_sync WHERE encounter_id = ?", encounterId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadEncounter(String encounterId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, case_token, visit_no, status, patient_json, created_at
            FROM pre_ai_encounters WHERE id = ?
            """, encounterId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ArrayNode publicMetrics(String value) {
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode source = readJson(value);
        if (!source.isArray()) return result;
        for (JsonNode metric : source) {
            if (text(metric.path("value")).isBlank()) continue;
            ObjectNode item = result.addObject();
            item.put("name", text(metric.path("name")));
            item.put("shortName", text(metric.path("shortName")));
            item.put("value", text(metric.path("value")));
            item.put("unit", text(metric.path("unit")));
            item.put("reference", text(metric.path("reference")));
            boolean critical = metric.path("critical").asBoolean(false) || "CRITICAL".equals(text(metric.path("severity")));
            item.put("severity", critical ? "CRITICAL" : text(metric.path("severity")));
            item.put("critical", critical);
        }
        return result;
    }

    private String identityKey(String type, String name, String suffix) {
        String normalized = name.replaceAll("\\s+", "").trim();
        return hmac(type.toLowerCase() + "|" + normalized + "|" + suffix.trim());
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("患者身份摘要失败", error);
        }
    }

    private String datePart(String value, String fallback) {
        if (value != null && value.length() >= 10) return value.substring(0, 10);
        if (fallback != null && fallback.length() >= 10) return fallback.substring(0, 10);
        return LocalDate.now().toString();
    }

    private String maskToken(String value) {
        if (blank(value)) return "";
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(blank(value) ? "[]" : value);
        } catch (Exception error) {
            return objectMapper.createArrayNode();
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("患者报告快照序列化失败", error);
        }
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(text(value)); } catch (Exception ignored) { return fallback; }
    }

    private String text(JsonNode node) { return node == null || node.isNull() ? "" : node.asText("").trim(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    private void requirePublisher(SessionUser user) {
        if (user == null || !PUBLISH_ROLES.contains(RoleCatalog.canonicalize(user.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员或医生可查看患者端同步状态");
        }
    }
}
