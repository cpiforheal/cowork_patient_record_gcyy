package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;
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
public class ClinicDatabaseService {

    private static final String REVISION_KEY = "revision";

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
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_db_meta (
              meta_key VARCHAR(64) PRIMARY KEY,
              meta_value VARCHAR(128) NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        ensureRevision();
    }

    public ObjectNode readDb() {
        ObjectNode db = objectMapper.createObjectNode();
        db.put("_revision", currentRevision());
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
    public ObjectNode patchDb(JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new IllegalArgumentException("clinic db patch payload must be an object");
        }
        ObjectNode db = readDb();
        mergePatch(db, patch);
        String revision = writeDb(db);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("_revision", revision);
        result.set("db", readDb());
        return result;
    }

    @Transactional
    public ObjectNode mergeDb(JsonNode incomingDb) {
        if (incomingDb == null || !incomingDb.isObject()) {
            throw new IllegalArgumentException("clinic db merge payload must be an object");
        }

        ObjectNode db = readDb();
        mergePatch(db, incomingDb);
        upsertMergedDb(db, incomingDb);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("_revision", updateRevision());
        result.set("db", readDb());
        return result;
    }

    public ObjectNode maintenanceStatus(Map<String, Object> fileStatus) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("revision", currentRevision());
        status.put("checkedAt", Instant.now().toString());
        status.put("patientCount", count("clinic_patients"));
        status.put("recordCount", count("clinic_record_fields"));
        status.put("documentCount", count("clinic_documents"));
        status.put("auditLogCount", count("clinic_audit_logs"));
        status.put("snapshotCount", count("clinic_db_snapshots"));
        status.put("latestSnapshotAt", latestSnapshotAt());
        status.set("storage", objectMapper.valueToTree(fileStatus));
        return status;
    }

    @Transactional
    public ObjectNode createSnapshot() {
        ObjectNode db = readDb();
        jdbcTemplate.update("INSERT INTO clinic_db_snapshots (payload_json) VALUES (?)", toJson(db));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("savedAt", latestSnapshotAt());
        result.put("snapshotCount", count("clinic_db_snapshots"));
        result.put("revision", currentRevision());
        return result;
    }

    public ArrayNode findDuplicatePatients() {
        ObjectNode db = readDb();
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

    @Transactional
    public String writeDb(JsonNode db) {
        if (db == null || !db.isObject()) {
            throw new IllegalArgumentException("clinic db payload must be an object");
        }

        String clientRevision = text(db, "_revision");
        String currentRevision = currentRevision();
        if (!clientRevision.isBlank() && !clientRevision.equals(currentRevision)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "数据已被其他终端更新，请刷新页面后再保存"
            );
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
        return updateRevision();
    }

    private void upsertMergedDb(ObjectNode mergedDb, JsonNode incomingDb) {
        upsertPatients(mergedDb.path("patients"), incomingDb.path("patients"));
        upsertRecords(mergedDb.path("records"), incomingDb.path("records"));
        upsertArchive(mergedDb.path("archive"), incomingDb.path("archive"));
        upsertDocuments(mergedDb.path("documents"), incomingDb.path("documents"));
        upsertSimpleArray("clinic_accounts", mergedDb.path("accounts"), incomingDb.path("accounts"), "id");
        upsertSimpleArray("clinic_roles", mergedDb.path("roles"), incomingDb.path("roles"), "id");
        upsertSimpleArray("clinic_departments", mergedDb.path("departments"), incomingDb.path("departments"), "id");
        upsertSimpleArray("clinic_dictionaries", mergedDb.path("dictionaries"), incomingDb.path("dictionaries"), "id");
        upsertSimpleArray("clinic_template_field_rules", mergedDb.path("templateFieldRules"), incomingDb.path("templateFieldRules"), "id");
        upsertSimpleArray("clinic_audit_logs", mergedDb.path("auditLogs"), incomingDb.path("auditLogs"), "id");
    }

    private void upsertPatients(JsonNode mergedPatients, JsonNode incomingPatients) {
        if (!incomingPatients.isArray()) return;
        for (JsonNode incomingPatient : incomingPatients) {
            String patientId = text(incomingPatient, "id");
            JsonNode patient = findArrayItemById(mergedPatients, "id", patientId);
            if (patient == null) continue;
            jdbcTemplate.update("DELETE FROM clinic_patient_encounters WHERE patient_id = ?", patientId);
            jdbcTemplate.update("""
                INSERT INTO clinic_patients (
                  id, name, visit_no, visit_date, visit_type, doctor, current_stage, completed_count,
                  progress_percent, status, risk_type, created_at, updated_at, encounter_count,
                  encounter_history_json, raw_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  name = VALUES(name), visit_no = VALUES(visit_no), visit_date = VALUES(visit_date),
                  visit_type = VALUES(visit_type), doctor = VALUES(doctor), current_stage = VALUES(current_stage),
                  completed_count = VALUES(completed_count), progress_percent = VALUES(progress_percent),
                  status = VALUES(status), risk_type = VALUES(risk_type), created_at = VALUES(created_at),
                  updated_at = VALUES(updated_at), encounter_count = VALUES(encounter_count),
                  encounter_history_json = VALUES(encounter_history_json), raw_json = VALUES(raw_json)
                """,
                patientId,
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
            writePatientEncounters(patientId, patient);
        }
    }

    private void upsertRecords(JsonNode mergedRecords, JsonNode incomingRecords) {
        if (!incomingRecords.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = incomingRecords.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode record = mergedRecords.path(entry.getKey());
            if (!record.isObject()) continue;
            jdbcTemplate.update("""
                INSERT INTO clinic_record_fields (patient_id, fields_json) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE fields_json = VALUES(fields_json)
                """,
                entry.getKey(),
                toJson(record)
            );
            jdbcTemplate.update("DELETE FROM clinic_record_field_values WHERE patient_id = ?", entry.getKey());
            writeRecordFieldValues(entry.getKey(), record);
        }
    }

    private void upsertArchive(JsonNode mergedArchive, JsonNode incomingArchive) {
        if (!incomingArchive.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = incomingArchive.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = mergedArchive.path(entry.getKey());
            if (!value.isObject()) continue;
            jdbcTemplate.update("""
                INSERT INTO clinic_archive (patient_id, submitted, version, generated_at, raw_json) VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE submitted = VALUES(submitted), version = VALUES(version),
                  generated_at = VALUES(generated_at), raw_json = VALUES(raw_json)
                """,
                entry.getKey(),
                value.path("submitted").asBoolean(false),
                text(value, "version"),
                text(value, "generatedAt"),
                toJson(value)
            );
        }
    }

    private void upsertDocuments(JsonNode mergedDocuments, JsonNode incomingDocuments) {
        if (!incomingDocuments.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> patientDocuments = incomingDocuments.fields();
        while (patientDocuments.hasNext()) {
            Map.Entry<String, JsonNode> entry = patientDocuments.next();
            JsonNode documents = mergedDocuments.path(entry.getKey());
            if (!documents.isArray()) continue;
            for (JsonNode document : documents) {
                jdbcTemplate.update("""
                    INSERT INTO clinic_documents (
                      document_key, patient_id, file_name, type, type_label, department, status,
                      storage_path, url, uploaded_at, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE file_name = VALUES(file_name), type = VALUES(type),
                      type_label = VALUES(type_label), department = VALUES(department), status = VALUES(status),
                      storage_path = VALUES(storage_path), url = VALUES(url), uploaded_at = VALUES(uploaded_at),
                      raw_json = VALUES(raw_json)
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

    private void upsertSimpleArray(String table, JsonNode mergedRows, JsonNode incomingRows, String idKey) {
        if (!incomingRows.isArray()) return;
        for (JsonNode incomingRow : incomingRows) {
            String id = text(incomingRow, idKey);
            JsonNode row = findArrayItemById(mergedRows, idKey, id);
            if (row == null) continue;
            switch (table) {
                case "clinic_accounts" -> jdbcTemplate.update("""
                    INSERT INTO clinic_accounts (id, username, role, status, raw_json) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE username = VALUES(username), role = VALUES(role),
                      status = VALUES(status), raw_json = VALUES(raw_json)
                    """,
                    id, text(row, "username"), text(row, "role"), text(row, "status"), toJson(row)
                );
                case "clinic_roles" -> jdbcTemplate.update("""
                    INSERT INTO clinic_roles (id, role, name, raw_json) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE role = VALUES(role), name = VALUES(name), raw_json = VALUES(raw_json)
                    """,
                    id, text(row, "role"), text(row, "name"), toJson(row)
                );
                case "clinic_departments" -> jdbcTemplate.update("""
                    INSERT INTO clinic_departments (id, name, raw_json) VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE name = VALUES(name), raw_json = VALUES(raw_json)
                    """,
                    id, text(row, "name"), toJson(row)
                );
                case "clinic_dictionaries" -> jdbcTemplate.update("""
                    INSERT INTO clinic_dictionaries (id, name, department, raw_json) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE name = VALUES(name), department = VALUES(department), raw_json = VALUES(raw_json)
                    """,
                    id, text(row, "name"), text(row, "department"), toJson(row)
                );
                case "clinic_template_field_rules" -> jdbcTemplate.update("""
                    INSERT INTO clinic_template_field_rules (
                      id, section_key, field_key, field_label, department, enabled, sort_no, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE section_key = VALUES(section_key), field_key = VALUES(field_key),
                      field_label = VALUES(field_label), department = VALUES(department), enabled = VALUES(enabled),
                      sort_no = VALUES(sort_no), raw_json = VALUES(raw_json)
                    """,
                    id, text(row, "sectionKey"), text(row, "fieldKey"), text(row, "fieldLabel"),
                    text(row, "department"), row.path("enabled").asBoolean(true), integer(row, "sortNo"), toJson(row)
                );
                case "clinic_audit_logs" -> jdbcTemplate.update("""
                    INSERT INTO clinic_audit_logs (
                      id, time, operator, role, patient, patient_id, module, action, result, raw_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE raw_json = VALUES(raw_json)
                    """,
                    id, text(row, "time"), text(row, "operator"), text(row, "role"), text(row, "patient"),
                    text(row, "patientId"), text(row, "module"), text(row, "action"), text(row, "result"), toJson(row)
                );
                default -> throw new IllegalArgumentException("Unsupported table: " + table);
            }
        }
    }

    private JsonNode findArrayItemById(JsonNode rows, String idKey, String id) {
        if (id == null || id.isBlank() || !rows.isArray()) return null;
        for (JsonNode row : rows) {
            if (id.equals(text(row, idKey))) return row;
        }
        return null;
    }

    private void mergePatch(ObjectNode db, JsonNode patch) {
        mergeArrayById(db.withArray("patients"), patch.path("patients"), "id");
        mergeObjectProperties((ObjectNode) db.path("records"), patch.path("records"));
        mergeObjectProperties((ObjectNode) db.path("archive"), patch.path("archive"));
        mergeDocuments((ObjectNode) db.path("documents"), patch.path("documents"));
        mergeArrayById(db.withArray("accounts"), patch.path("accounts"), "id");
        mergeArrayById(db.withArray("roles"), patch.path("roles"), "id");
        mergeArrayById(db.withArray("departments"), patch.path("departments"), "id");
        mergeArrayById(db.withArray("dictionaries"), patch.path("dictionaries"), "id");
        mergeArrayById(db.withArray("templateFieldRules"), patch.path("templateFieldRules"), "id");
        mergeAuditLogs(db.withArray("auditLogs"), patch.path("auditLogs"));
    }

    private void mergeObjectProperties(ObjectNode target, JsonNode values) {
        if (!values.isObject()) return;
        values.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
    }

    private void mergeDocuments(ObjectNode target, JsonNode values) {
        if (!values.isObject()) return;
        values.fields().forEachRemaining(entry -> {
            ArrayNode documents = target.has(entry.getKey()) && target.get(entry.getKey()).isArray()
                ? (ArrayNode) target.get(entry.getKey())
                : target.putArray(entry.getKey());
            mergeArrayById(documents, entry.getValue(), "key");
        });
    }

    private void mergeAuditLogs(ArrayNode target, JsonNode values) {
        if (!values.isArray()) return;
        mergeArrayById(target, values, "id");
    }

    private void mergeArrayById(ArrayNode target, JsonNode values, String idKey) {
        if (!values.isArray()) return;
        Map<String, Integer> indexById = new LinkedHashMap<>();
        for (int index = 0; index < target.size(); index++) {
            indexById.put(text(target.get(index), idKey), index);
        }
        for (JsonNode value : values) {
            String id = text(value, idKey);
            if (!id.isBlank() && indexById.containsKey(id)) {
                target.set(indexById.get(id), value);
            } else {
                target.insert(0, value);
            }
        }
    }

    private int count(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private String latestSnapshotAt() {
        Timestamp timestamp = jdbcTemplate.queryForObject("SELECT MAX(saved_at) FROM clinic_db_snapshots", Timestamp.class);
        return timestamp == null ? "" : timestamp.toInstant().toString();
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

    private void ensureRevision() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_db_meta WHERE meta_key = ?",
            Integer.class,
            REVISION_KEY
        );
        if (count == null || count == 0) {
            jdbcTemplate.update(
                "INSERT INTO clinic_db_meta (meta_key, meta_value) VALUES (?, ?)",
                REVISION_KEY,
                newRevision()
            );
        }
    }

    private String currentRevision() {
        ensureRevision();
        return jdbcTemplate.queryForObject(
            "SELECT meta_value FROM clinic_db_meta WHERE meta_key = ?",
            String.class,
            REVISION_KEY
        );
    }

    private String updateRevision() {
        String revision = newRevision();
        jdbcTemplate.update(
            "UPDATE clinic_db_meta SET meta_value = ? WHERE meta_key = ?",
            revision,
            REVISION_KEY
        );
        return revision;
    }

    private String newRevision() {
        return UUID.randomUUID().toString();
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
