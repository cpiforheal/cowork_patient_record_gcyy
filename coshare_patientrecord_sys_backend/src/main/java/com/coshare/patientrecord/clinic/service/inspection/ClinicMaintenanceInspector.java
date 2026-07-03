package com.coshare.patientrecord.clinic.service.inspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicMaintenanceInspector {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClinicMaintenanceInspector(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ObjectNode maintenanceStatus(String revision, Map<String, Object> fileStatus) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("revision", revision);
        status.put("checkedAt", Instant.now().toString());
        status.put("patientCount", count("clinic_patients"));
        status.put("recordCount", count("clinic_record_fields"));
        status.put("documentCount", count("clinic_documents"));
        status.put("auditLogCount", count("clinic_audit_logs"));
        status.put("snapshotCount", snapshotCount());
        status.put("latestSnapshotAt", latestSnapshotAt());
        status.set("storage", objectMapper.valueToTree(fileStatus));
        return status;
    }

    public ArrayNode findDuplicatePatients(ObjectNode db) {
        Map<String, ArrayNode> groups = new LinkedHashMap<>();
        for (JsonNode patient : db.path("patients")) {
            String patientId = text(patient, "id");
            String name = normalizeIdentity(text(patient, "name"));
            JsonNode record = db.path("records").path(patientId);
            String phone = normalizeIdentity(text(record, "phone"));
            if (!name.isBlank() && !phone.isBlank()) {
                groups.computeIfAbsent("identity:" + name + ":" + phone, ignored -> objectMapper.createArrayNode()).add(patient);
            }
            collectVisitNos(patient).forEach(visitNo -> {
                if (!visitNo.isBlank()) {
                    groups.computeIfAbsent("visitNo:" + normalizeIdentity(visitNo), ignored -> objectMapper.createArrayNode()).add(patient);
                }
            });
        }

        ArrayNode duplicates = objectMapper.createArrayNode();
        groups.forEach((key, patients) -> {
            Map<String, JsonNode> uniquePatients = new LinkedHashMap<>();
            patients.forEach(patient -> uniquePatients.put(text(patient, "id"), patient));
            if (uniquePatients.size() > 1) {
                ObjectNode group = objectMapper.createObjectNode();
                group.put("key", key);
                group.put("reason", key.startsWith("identity:") ? "same_name_phone" : "same_visit_no");
                ArrayNode rows = objectMapper.createArrayNode();
                uniquePatients.values().forEach(rows::add);
                group.set("patients", rows);
                duplicates.add(group);
            }
        });
        return duplicates;
    }

    public List<String> referencedStoragePaths() {
        List<String> paths = new ArrayList<>();
        jdbcTemplate.query("SELECT storage_path FROM clinic_documents WHERE storage_path IS NOT NULL AND storage_path <> ''", resultSet -> {
            paths.add(resultSet.getString("storage_path"));
        });
        return paths;
    }

    public int snapshotCount() {
        return count("clinic_db_snapshots");
    }

    public String latestSnapshotAt() {
        Timestamp timestamp = jdbcTemplate.queryForObject("SELECT MAX(saved_at) FROM clinic_db_snapshots", Timestamp.class);
        return timestamp == null ? "" : timestamp.toInstant().toString();
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> collectVisitNos(JsonNode patient) {
        List<String> visitNos = new ArrayList<>();
        visitNos.add(text(patient, "visitNo"));
        JsonNode encounters = patient.path("encounterHistory");
        if (encounters.isArray()) {
            encounters.forEach(encounter -> visitNos.add(text(encounter, "visitNo")));
        }
        return visitNos;
    }

    private String normalizeIdentity(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    private String text(JsonNode node, String key) {
        return node == null || node.path(key).isMissingNode() || node.path(key).isNull() ? "" : node.path(key).asText("");
    }
}
