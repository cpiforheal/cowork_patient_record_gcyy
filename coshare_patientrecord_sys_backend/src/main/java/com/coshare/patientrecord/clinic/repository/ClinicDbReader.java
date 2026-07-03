package com.coshare.patientrecord.clinic.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicDbReader {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClinicDbReader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ObjectNode readDb(String revision) {
        ObjectNode db = objectMapper.createObjectNode();
        db.put("_revision", revision);
        db.set("patients", readPatients());
        db.set("records", readRecords());
        db.set("archive", readArchive());
        db.set("documents", readDocuments());
        db.set("accounts", readAccounts());
        db.set("roles", readArray("clinic_roles", "raw_json", "name ASC"));
        db.set("departments", readArray("clinic_departments", "raw_json", "name ASC"));
        db.set("dictionaries", readArray("clinic_dictionaries", "raw_json", "name ASC"));
        db.set("templateFieldRules", readArray("clinic_template_field_rules", "raw_json", "sort_no ASC, id ASC"));
        db.set("auditLogs", readArray("clinic_audit_logs", "raw_json", "time DESC, id DESC"));
        return db;
    }

    public ArrayNode readTemplateFieldRules() {
        return readArray("clinic_template_field_rules", "raw_json", "sort_no ASC, id ASC");
    }

    public JsonNode readDocumentByStoragePath(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) return null;
        List<JsonNode> rows = jdbcTemplate.query(
            "SELECT raw_json FROM clinic_documents WHERE storage_path = ? LIMIT 1",
            (resultSet, rowNum) -> readJson(resultSet, "raw_json"),
            storagePath
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ArrayNode readArray(String table, String jsonColumn, String orderBy) {
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT " + jsonColumn + " FROM " + table + " ORDER BY " + orderBy, resultSet -> {
            rows.add(readJson(resultSet, jsonColumn));
        });
        return rows;
    }

    private ArrayNode readAccounts() {
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT raw_json FROM clinic_accounts ORDER BY username ASC", resultSet -> {
            JsonNode raw = readJson(resultSet, "raw_json");
            ObjectNode account = raw.isObject() ? (ObjectNode) raw.deepCopy() : objectMapper.createObjectNode();
            account.remove(List.of("password", "passwordHash"));
            rows.add(account);
        });
        return rows;
    }

    private ArrayNode readPatients() {
        ArrayNode rows = objectMapper.createArrayNode();
        Map<String, ObjectNode> patientById = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, raw_json FROM clinic_patients ORDER BY updated_at DESC, created_at DESC", resultSet -> {
            JsonNode raw = readJson(resultSet, "raw_json");
            ObjectNode patient = raw.isObject() ? (ObjectNode) raw.deepCopy() : objectMapper.createObjectNode();
            patientById.put(resultSet.getString("id"), patient);
            rows.add(patient);
        });

        Map<String, ArrayNode> encountersByPatient = new LinkedHashMap<>();
        jdbcTemplate.query(
            "SELECT patient_id, raw_json FROM clinic_patient_encounters ORDER BY patient_id ASC, sort_no ASC, visit_date ASC",
            resultSet -> {
                String patientId = resultSet.getString("patient_id");
                ObjectNode patient = patientById.get(patientId);
                if (patient == null) return;
                ArrayNode encounters = encountersByPatient.computeIfAbsent(patientId, ignored -> {
                    ArrayNode created = objectMapper.createArrayNode();
                    patient.set("encounterHistory", created);
                    return created;
                });
                encounters.add(readJson(resultSet, "raw_json"));
            }
        );
        return rows;
    }

    private ObjectNode readRecords() {
        ObjectNode records = objectMapper.createObjectNode();
        Integer normalizedCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_record_field_values", Integer.class);
        if (normalizedCount != null && normalizedCount > 0) {
            jdbcTemplate.query(
                "SELECT patient_id, field_key, raw_json FROM clinic_record_field_values ORDER BY patient_id ASC, field_key ASC",
                resultSet -> {
                    String patientId = resultSet.getString("patient_id");
                    ObjectNode patientRecord = records.has(patientId) && records.get(patientId).isObject()
                        ? (ObjectNode) records.get(patientId)
                        : records.putObject(patientId);
                    JsonNode raw = readJson(resultSet, "raw_json");
                    JsonNode value = raw.has("value") ? raw.get("value") : raw;
                    patientRecord.put(resultSet.getString("field_key"), valueText(value));
                }
            );
            return records;
        }

        jdbcTemplate.query("SELECT patient_id, fields_json FROM clinic_record_fields", resultSet -> {
            records.set(resultSet.getString("patient_id"), readJson(resultSet, "fields_json"));
        });
        return records;
    }

    private ObjectNode readArchive() {
        ObjectNode archive = objectMapper.createObjectNode();
        jdbcTemplate.query("SELECT patient_id, raw_json FROM clinic_archive", resultSet -> {
            archive.set(resultSet.getString("patient_id"), readJson(resultSet, "raw_json"));
        });
        return archive;
    }

    private ObjectNode readDocuments() {
        ObjectNode documents = objectMapper.createObjectNode();
        jdbcTemplate.query("SELECT patient_id, raw_json FROM clinic_documents ORDER BY uploaded_at ASC, document_key ASC", resultSet -> {
            String patientId = resultSet.getString("patient_id");
            ArrayNode patientDocuments = documents.has(patientId) && documents.get(patientId).isArray()
                ? (ArrayNode) documents.get(patientId)
                : documents.putArray(patientId);
            patientDocuments.add(readJson(resultSet, "raw_json"));
        });
        return documents;
    }

    private JsonNode readJson(ResultSet resultSet, String column) throws SQLException {
        try {
            String rawJson = resultSet.getString(column);
            if (rawJson == null || rawJson.isBlank()) return objectMapper.createObjectNode();
            JsonNode node = objectMapper.readTree(rawJson);
            return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
        } catch (Exception error) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to serialize JSON", error);
        }
    }

    private String text(JsonNode node, String key) {
        return text(node, key, "");
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private String valueText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        return value.isValueNode() ? value.asText() : toJson(value);
    }
}
