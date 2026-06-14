package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class ClinicDatabaseService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClinicDatabaseService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_patients (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(100) NOT NULL,
              visit_no VARCHAR(100),
              visit_date VARCHAR(32),
              visit_type VARCHAR(32),
              doctor VARCHAR(100),
              current_stage VARCHAR(100),
              completed_count INT DEFAULT 0,
              progress_percent INT DEFAULT 0,
              status VARCHAR(100),
              risk_type VARCHAR(32),
              created_at VARCHAR(32),
              updated_at VARCHAR(32),
              encounter_count INT DEFAULT 1,
              encounter_history_json JSON NULL,
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_record_fields (
              patient_id VARCHAR(64) PRIMARY KEY,
              fields_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_patient_encounters (
              id VARCHAR(128) PRIMARY KEY,
              patient_id VARCHAR(64) NOT NULL,
              visit_no VARCHAR(100),
              visit_date VARCHAR(32),
              visit_type VARCHAR(32),
              doctor VARCHAR(100),
              created_at VARCHAR(32),
              sort_no INT DEFAULT 0,
              raw_json JSON NOT NULL,
              INDEX idx_clinic_patient_encounters_patient (patient_id),
              INDEX idx_clinic_patient_encounters_date (visit_date),
              INDEX idx_clinic_patient_encounters_visit_no (visit_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_record_field_values (
              patient_id VARCHAR(64) NOT NULL,
              field_key VARCHAR(128) NOT NULL,
              field_value TEXT,
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (patient_id, field_key),
              INDEX idx_clinic_record_field_values_field (field_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_archive (
              patient_id VARCHAR(64) PRIMARY KEY,
              submitted BOOLEAN NOT NULL DEFAULT FALSE,
              version VARCHAR(100),
              generated_at VARCHAR(32),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_documents (
              document_key VARCHAR(128) PRIMARY KEY,
              patient_id VARCHAR(64) NOT NULL,
              file_name VARCHAR(255),
              type VARCHAR(100),
              type_label VARCHAR(100),
              department VARCHAR(100),
              status VARCHAR(32),
              storage_path VARCHAR(512),
              url VARCHAR(512),
              uploaded_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_clinic_documents_patient (patient_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_accounts (
              id VARCHAR(64) PRIMARY KEY,
              username VARCHAR(100),
              role VARCHAR(64),
              status VARCHAR(32),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_roles (
              id VARCHAR(64) PRIMARY KEY,
              role VARCHAR(64),
              name VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_departments (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_dictionaries (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(100),
              department VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_template_field_rules (
              id VARCHAR(128) PRIMARY KEY,
              section_key VARCHAR(100),
              field_key VARCHAR(100),
              field_label VARCHAR(100),
              department VARCHAR(100),
              enabled BOOLEAN,
              sort_no INT DEFAULT 0,
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_audit_logs (
              id VARCHAR(128) PRIMARY KEY,
              time VARCHAR(32),
              operator VARCHAR(100),
              role VARCHAR(64),
              patient VARCHAR(100),
              patient_id VARCHAR(64),
              module VARCHAR(64),
              action VARCHAR(100),
              result VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_clinic_audit_logs_patient (patient_id),
              INDEX idx_clinic_audit_logs_time (time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_db_snapshots (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              payload_json JSON NOT NULL,
              saved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }

    public ObjectNode readDb() {
        ObjectNode db = objectMapper.createObjectNode();
        db.set("patients", readPatients());
        db.set("records", readRecords());
        db.set("archive", readArchive());
        db.set("documents", readDocuments());
        db.set("accounts", readArray("clinic_accounts", "raw_json", "username ASC"));
        db.set("roles", readArray("clinic_roles", "raw_json", "name ASC"));
        db.set("departments", readArray("clinic_departments", "raw_json", "name ASC"));
        db.set("dictionaries", readArray("clinic_dictionaries", "raw_json", "name ASC"));
        db.set("templateFieldRules", readArray("clinic_template_field_rules", "raw_json", "sort_no ASC, id ASC"));
        db.set("auditLogs", readArray("clinic_audit_logs", "raw_json", "time DESC, id DESC"));
        return db;
    }

    @Transactional
    public void writeDb(JsonNode db) {
        if (db == null || !db.isObject()) {
            throw new IllegalArgumentException("clinic db payload must be an object");
        }

        clearTables();
        writePatients(db.path("patients"));
        writeRecords(db.path("records"));
        writeArchive(db.path("archive"));
        writeDocuments(db.path("documents"));
        writeSimpleArray("clinic_accounts", db.path("accounts"), List.of("id", "username", "role", "status"));
        writeSimpleArray("clinic_roles", db.path("roles"), List.of("id", "role", "name"));
        writeSimpleArray("clinic_departments", db.path("departments"), List.of("id", "name"));
        writeSimpleArray("clinic_dictionaries", db.path("dictionaries"), List.of("id", "name", "department"));
        writeSimpleArray("clinic_template_field_rules", db.path("templateFieldRules"), List.of("id", "sectionKey", "fieldKey", "fieldLabel", "department", "enabled", "sortNo"));
        writeSimpleArray("clinic_audit_logs", db.path("auditLogs"), List.of("id", "time", "operator", "role", "patient", "patientId", "module", "action", "result"));

        jdbcTemplate.update("INSERT INTO clinic_db_snapshots (payload_json) VALUES (?)", toJson(db));
    }

    private void clearTables() {
        for (String table : List.of(
            "clinic_record_field_values",
            "clinic_patient_encounters",
            "clinic_patients",
            "clinic_record_fields",
            "clinic_archive",
            "clinic_documents",
            "clinic_accounts",
            "clinic_roles",
            "clinic_departments",
            "clinic_dictionaries",
            "clinic_template_field_rules",
            "clinic_audit_logs"
        )) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }

    private void writePatients(JsonNode patients) {
        if (!patients.isArray()) return;
        for (JsonNode patient : patients) {
            jdbcTemplate.update("""
                INSERT INTO clinic_patients (
                  id, name, visit_no, visit_date, visit_type, doctor, current_stage, completed_count,
                  progress_percent, status, risk_type, created_at, updated_at, encounter_count,
                  encounter_history_json, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                text(patient, "id"),
                text(patient, "name"),
                text(patient, "visitNo"),
                text(patient, "visitDate"),
                text(patient, "visitType"),
                text(patient, "doctor"),
                text(patient, "currentStage"),
                integer(patient, "completedCount"),
                integer(patient, "progressPercent"),
                text(patient, "status"),
                text(patient, "riskType"),
                text(patient, "createdAt"),
                text(patient, "updatedAt"),
                integer(patient, "encounterCount"),
                toJson(patient.path("encounterHistory").isMissingNode() ? objectMapper.createArrayNode() : patient.path("encounterHistory")),
                toJson(patient)
            );
            writePatientEncounters(text(patient, "id"), patient);
        }
    }

    private void writeRecords(JsonNode records) {
        if (!records.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = records.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            jdbcTemplate.update(
                "INSERT INTO clinic_record_fields (patient_id, fields_json) VALUES (?, ?)",
                entry.getKey(),
                toJson(entry.getValue())
            );
            writeRecordFieldValues(entry.getKey(), entry.getValue());
        }
    }

    private void writePatientEncounters(String patientId, JsonNode patient) {
        JsonNode encounters = patient.path("encounterHistory");
        if (!encounters.isArray() || encounters.isEmpty()) {
            ObjectNode encounter = objectMapper.createObjectNode();
            encounter.put("id", "enc-" + patientId + "-initial");
            encounter.put("visitNo", text(patient, "visitNo"));
            encounter.put("visitDate", text(patient, "visitDate"));
            encounter.put("visitType", text(patient, "visitType"));
            encounter.put("doctor", text(patient, "doctor"));
            encounter.put("createdAt", text(patient, "createdAt"));
            ArrayNode generated = objectMapper.createArrayNode();
            generated.add(encounter);
            encounters = generated;
        }

        int sortNo = 0;
        for (JsonNode encounter : encounters) {
            jdbcTemplate.update("""
                INSERT INTO clinic_patient_encounters (
                  id, patient_id, visit_no, visit_date, visit_type, doctor, created_at, sort_no, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                text(encounter, "id", patientId + "-enc-" + sortNo),
                patientId,
                text(encounter, "visitNo"),
                text(encounter, "visitDate"),
                text(encounter, "visitType"),
                text(encounter, "doctor"),
                text(encounter, "createdAt"),
                sortNo,
                toJson(encounter)
            );
            sortNo += 1;
        }
    }

    private void writeRecordFieldValues(String patientId, JsonNode fields) {
        if (!fields.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> values = fields.fields();
        while (values.hasNext()) {
            Map.Entry<String, JsonNode> entry = values.next();
            ObjectNode raw = objectMapper.createObjectNode();
            raw.set("value", entry.getValue());
            jdbcTemplate.update(
                "INSERT INTO clinic_record_field_values (patient_id, field_key, field_value, raw_json) VALUES (?, ?, ?, ?)",
                patientId,
                entry.getKey(),
                valueText(entry.getValue()),
                toJson(raw)
            );
        }
    }

    private void writeArchive(JsonNode archive) {
        if (!archive.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = archive.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            jdbcTemplate.update(
                "INSERT INTO clinic_archive (patient_id, submitted, version, generated_at, raw_json) VALUES (?, ?, ?, ?, ?)",
                entry.getKey(),
                value.path("submitted").asBoolean(false),
                text(value, "version"),
                text(value, "generatedAt"),
                toJson(value)
            );
        }
    }

    private void writeDocuments(JsonNode documents) {
        if (!documents.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> patientDocuments = documents.fields();
        while (patientDocuments.hasNext()) {
            Map.Entry<String, JsonNode> entry = patientDocuments.next();
            if (!entry.getValue().isArray()) continue;
            for (JsonNode document : entry.getValue()) {
                jdbcTemplate.update("""
                    INSERT INTO clinic_documents (
                      document_key, patient_id, file_name, type, type_label, department, status,
                      storage_path, url, uploaded_at, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    text(document, "key", text(document, "id", entry.getKey() + "-" + System.nanoTime())),
                    entry.getKey(),
                    text(document, "fileName"),
                    text(document, "type"),
                    text(document, "typeLabel"),
                    text(document, "department"),
                    text(document, "status"),
                    text(document, "storagePath"),
                    text(document, "url"),
                    text(document, "uploadedAt", text(document, "time")),
                    toJson(document)
                );
            }
        }
    }

    private void writeSimpleArray(String table, JsonNode rows, List<String> columns) {
        if (!rows.isArray()) return;
        for (JsonNode row : rows) {
            switch (table) {
                case "clinic_accounts" -> jdbcTemplate.update(
                    "INSERT INTO clinic_accounts (id, username, role, status, raw_json) VALUES (?, ?, ?, ?, ?)",
                    text(row, "id"), text(row, "username"), text(row, "role"), text(row, "status"), toJson(row)
                );
                case "clinic_roles" -> jdbcTemplate.update(
                    "INSERT INTO clinic_roles (id, role, name, raw_json) VALUES (?, ?, ?, ?)",
                    text(row, "id"), text(row, "role"), text(row, "name"), toJson(row)
                );
                case "clinic_departments" -> jdbcTemplate.update(
                    "INSERT INTO clinic_departments (id, name, raw_json) VALUES (?, ?, ?)",
                    text(row, "id"), text(row, "name"), toJson(row)
                );
                case "clinic_dictionaries" -> jdbcTemplate.update(
                    "INSERT INTO clinic_dictionaries (id, name, department, raw_json) VALUES (?, ?, ?, ?)",
                    text(row, "id"), text(row, "name"), text(row, "department"), toJson(row)
                );
                case "clinic_template_field_rules" -> jdbcTemplate.update(
                    """
                    INSERT INTO clinic_template_field_rules (
                      id, section_key, field_key, field_label, department, enabled, sort_no, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    text(row, "id"), text(row, "sectionKey"), text(row, "fieldKey"), text(row, "fieldLabel"),
                    text(row, "department"), row.path("enabled").asBoolean(true), integer(row, "sortNo"), toJson(row)
                );
                case "clinic_audit_logs" -> jdbcTemplate.update(
                    """
                    INSERT INTO clinic_audit_logs (
                      id, time, operator, role, patient, patient_id, module, action, result, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    text(row, "id"), text(row, "time"), text(row, "operator"), text(row, "role"), text(row, "patient"),
                    text(row, "patientId"), text(row, "module"), text(row, "action"), text(row, "result"), toJson(row)
                );
                default -> throw new IllegalArgumentException("Unsupported table: " + table + " " + columns);
            }
        }
    }

    private ArrayNode readArray(String table, String jsonColumn, String orderBy) {
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query("SELECT " + jsonColumn + " FROM " + table + " ORDER BY " + orderBy, resultSet -> {
            rows.add(readJson(resultSet, jsonColumn));
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
            return objectMapper.readTree(resultSet.getString(column));
        } catch (Exception error) {
            throw new SQLException("Failed to parse JSON column " + column, error);
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

    private int integer(JsonNode node, String key) {
        return node.path(key).asInt(0);
    }

    private String valueText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "";
        return value.isValueNode() ? value.asText() : toJson(value);
    }
}
