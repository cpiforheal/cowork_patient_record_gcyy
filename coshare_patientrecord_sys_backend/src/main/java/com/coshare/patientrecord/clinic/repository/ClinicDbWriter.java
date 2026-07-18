package com.coshare.patientrecord.clinic.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("mysql")
public class ClinicDbWriter {

    private static final String REVISION_KEY = "revision";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;

    public ClinicDbWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
    }

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

        ObjectNode writableDb = ((ObjectNode) db).deepCopy();
        hydrateAccountPasswords(writableDb);

        clearTables();
        writePatients(writableDb.path("patients"));
        writeRecords(writableDb.path("records"));
        writeArchive(writableDb.path("archive"));
        writeDocuments(writableDb.path("documents"));
        // Accounts and departments use stable identifiers and may already be referenced by
        // encounters/audit data. A full legacy-database save therefore updates them in place
        // instead of deleting and recreating them. Role policy is server-owned and read-only.
        upsertSimpleArray("clinic_departments", writableDb.path("departments"), writableDb.path("departments"), "id");
        upsertSimpleArray("clinic_accounts", writableDb.path("accounts"), writableDb.path("accounts"), "id");
        writeSimpleArray("clinic_dictionaries", writableDb.path("dictionaries"), List.of("id", "name", "department"));
        writeSimpleArray("clinic_template_field_rules", writableDb.path("templateFieldRules"), List.of("id", "sectionKey", "fieldKey", "fieldLabel", "department", "enabled", "sortNo"));
        writeSimpleArray("clinic_audit_logs", writableDb.path("auditLogs"), List.of("id", "time", "operator", "role", "patient", "patientId", "module", "action", "result"));
        revokeSessionsForUnavailableAccounts();

        jdbcTemplate.update("INSERT INTO clinic_db_snapshots (payload_json) VALUES (?)", toJson(writableDb));
        return updateRevision();
    }

    public void saveSnapshot(JsonNode db) {
        jdbcTemplate.update("INSERT INTO clinic_db_snapshots (payload_json) VALUES (?)", toJson(db));
    }

    public void upsertMergedDb(ObjectNode mergedDb, JsonNode incomingDb) {
        upsertPatients(mergedDb.path("patients"), incomingDb.path("patients"));
        upsertRecords(mergedDb.path("records"), incomingDb.path("records"));
        upsertArchive(mergedDb.path("archive"), incomingDb.path("archive"));
        upsertDocuments(mergedDb.path("documents"), incomingDb.path("documents"));
        upsertSimpleArray("clinic_departments", mergedDb.path("departments"), incomingDb.path("departments"), "id");
        upsertSimpleArray("clinic_accounts", mergedDb.path("accounts"), incomingDb.path("accounts"), "id");
        upsertSimpleArray("clinic_dictionaries", mergedDb.path("dictionaries"), incomingDb.path("dictionaries"), "id");
        upsertSimpleArray("clinic_template_field_rules", mergedDb.path("templateFieldRules"), incomingDb.path("templateFieldRules"), "id");
        upsertSimpleArray("clinic_audit_logs", mergedDb.path("auditLogs"), incomingDb.path("auditLogs"), "id");
    }

    private void upsertPatients(JsonNode mergedPatients, JsonNode incomingPatients) {
        if (!incomingPatients.isArray()) return;
        for (JsonNode incomingPatient : incomingPatients) {
            String patientId = text(incomingPatient, "id");
            JsonNode patient = findArrayItemById(mergedPatients, "id", patientId);
            if (patient == null) patient = incomingPatient;
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
            if (row == null) row = incomingRow;
            switch (table) {
                case "clinic_accounts" -> {
                    ObjectNode account = normalizeAccountDepartments(secureAccountRow(row));
                    jdbcTemplate.update("""
                    INSERT INTO clinic_accounts (id, username, role, status, raw_json) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE username = VALUES(username), role = VALUES(role),
                      status = VALUES(status), raw_json = VALUES(raw_json)
                    """,
                    id, text(account, "username"), text(account, "role"), text(account, "status"), toJson(account)
                    );
                    synchronizeAccountDepartments(account);
                    revokeSessionsIfDisabled(account);
                }
                case "clinic_roles" -> {
                    // Role definitions are the fixed server policy. Ignore legacy client writes.
                }
                case "clinic_departments" -> {
                    ObjectNode department = normalizeDepartmentRow(row);
                    jdbcTemplate.update("""
                    INSERT INTO clinic_departments (id, code, name, status, raw_json) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE code = VALUES(code), name = VALUES(name),
                      status = VALUES(status), raw_json = VALUES(raw_json)
                    """,
                    id, text(department, "code"), text(department, "name"),
                    text(department, "status"), toJson(department)
                    );
                }
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

    private ObjectNode secureAccountRow(JsonNode row) {
        ObjectNode account = asObject(row).deepCopy();
        String id = text(account, "id");
        String incomingPasswordHash = text(account, "passwordHash");
        String incomingPassword = text(account, "password");
        account.remove(List.of("password", "currentPassword"));
        if (!incomingPasswordHash.isBlank()) {
            account.put("passwordHash", isBcrypt(incomingPasswordHash) ? incomingPasswordHash : passwordEncoder.encode(incomingPasswordHash));
            return account;
        }
        if (!incomingPassword.isBlank()) {
            account.put("passwordHash", passwordEncoder.encode(incomingPassword));
            return account;
        }
        String existingPasswordHash = existingAccountPasswordHash(id);
        if (!existingPasswordHash.isBlank()) {
            account.put("passwordHash", isBcrypt(existingPasswordHash) ? existingPasswordHash : passwordEncoder.encode(existingPasswordHash));
        }
        return account;
    }

    private String existingAccountPasswordHash(String id) {
        if (id.isBlank()) return "";
        List<String> values = jdbcTemplate.query(
            "SELECT raw_json FROM clinic_accounts WHERE id = ? LIMIT 1",
            (resultSet, rowNum) -> {
                JsonNode raw = readJson(resultSet, "raw_json");
                return text(raw, "passwordHash");
            },
            id
        );
        return values.isEmpty() ? "" : values.get(0);
    }

    private void hydrateAccountPasswords(ObjectNode db) {
        JsonNode accounts = db.path("accounts");
        if (!accounts.isArray()) return;
        for (JsonNode row : accounts) {
            if (!row.isObject()) continue;
            ObjectNode account = (ObjectNode) row;
            if (!text(account, "passwordHash").isBlank() || !text(account, "password").isBlank()) continue;
            String existingPassword = existingAccountPasswordHash(text(account, "id"));
            if (!existingPassword.isBlank()) {
                account.put("passwordHash", isBcrypt(existingPassword) ? existingPassword : passwordEncoder.encode(existingPassword));
            }
        }
    }

    public void migrateLegacyAccountPasswords() {
        List<JsonNode> accounts = jdbcTemplate.query(
            "SELECT raw_json FROM clinic_accounts",
            (resultSet, rowNum) -> readJson(resultSet, "raw_json")
        );
        for (JsonNode account : accounts) {
            ObjectNode secured = asObject(account).deepCopy();
            secured.remove(List.of("password", "currentPassword"));
            String passwordHash = text(secured, "passwordHash");
            if (!passwordHash.isBlank() && !isBcrypt(passwordHash)) secured.remove("passwordHash");
            if (secured.equals(account)) continue;
            jdbcTemplate.update("UPDATE clinic_accounts SET raw_json = ? WHERE id = ?", toJson(secured), text(secured, "id"));
        }
    }

    private ObjectNode asObject(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
    }

    private boolean isBcrypt(String value) {
        return value != null && (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$"));
    }

    private void clearTables() {
        for (String table : List.of(
            "clinic_record_field_values",
            "clinic_patient_encounters",
            "clinic_patients",
            "clinic_record_fields",
            "clinic_archive",
            "clinic_documents",
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
                case "clinic_accounts" -> {
                    ObjectNode account = normalizeAccountDepartments(secureAccountRow(row));
                    jdbcTemplate.update("""
                    INSERT INTO clinic_accounts (id, username, role, status, raw_json) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE username = VALUES(username), role = VALUES(role),
                      status = VALUES(status), raw_json = VALUES(raw_json)
                    """,
                    text(account, "id"), text(account, "username"), text(account, "role"), text(account, "status"), toJson(account)
                    );
                    synchronizeAccountDepartments(account);
                    revokeSessionsIfDisabled(account);
                }
                case "clinic_roles" -> {
                    // Role definitions are the fixed server policy. Ignore legacy client writes.
                }
                case "clinic_departments" -> {
                    ObjectNode department = normalizeDepartmentRow(row);
                    jdbcTemplate.update("""
                    INSERT INTO clinic_departments (id, code, name, status, raw_json) VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE code = VALUES(code), name = VALUES(name),
                      status = VALUES(status), raw_json = VALUES(raw_json)
                    """,
                    text(department, "id"), text(department, "code"), text(department, "name"),
                    text(department, "status"), toJson(department)
                    );
                }
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

    private ObjectNode normalizeDepartmentRow(JsonNode row) {
        ObjectNode department = asObject(row).deepCopy();
        String id = text(department, "id");
        String code = text(department, "code").trim().toUpperCase(Locale.ROOT);
        if (code.isBlank()) {
            code = "DEPT_" + Integer.toUnsignedString(id.hashCode(), 36).toUpperCase(Locale.ROOT);
        }
        String status = text(department, "status", "ACTIVE").trim().toUpperCase(Locale.ROOT);
        if (!"INACTIVE".equals(status)) status = "ACTIVE";
        department.put("code", code);
        department.put("status", status);
        return department;
    }

    private ObjectNode normalizeAccountDepartments(ObjectNode account) {
        Set<String> departmentIds = new LinkedHashSet<>();
        JsonNode incomingDepartmentIds = account.path("departmentIds");
        if (incomingDepartmentIds.isArray()) {
            for (JsonNode value : incomingDepartmentIds) {
                if (!value.asText().isBlank()) departmentIds.add(value.asText());
            }
        }

        String primaryDepartmentId = text(account, "primaryDepartmentId");
        if (departmentIds.isEmpty()) {
            String legacyDepartmentName = text(account, "department");
            if (!legacyDepartmentName.isBlank()) {
                List<String> ids = jdbcTemplate.query(
                    "SELECT id FROM clinic_departments WHERE name = ? AND status = 'ACTIVE' ORDER BY id LIMIT 1",
                    (resultSet, rowNum) -> resultSet.getString("id"),
                    legacyDepartmentName
                );
                if (!ids.isEmpty()) departmentIds.add(ids.get(0));
            }
        }
        if (!primaryDepartmentId.isBlank()) departmentIds.add(primaryDepartmentId);

        Set<String> validDepartmentIds = new LinkedHashSet<>();
        Map<String, String> departmentNames = new java.util.LinkedHashMap<>();
        for (String departmentId : departmentIds) {
            List<String> names = jdbcTemplate.query(
                "SELECT name FROM clinic_departments WHERE id = ? AND status = 'ACTIVE' LIMIT 1",
                (resultSet, rowNum) -> resultSet.getString("name"),
                departmentId
            );
            if (!names.isEmpty()) {
                validDepartmentIds.add(departmentId);
                departmentNames.put(departmentId, names.get(0));
            }
        }
        if (validDepartmentIds.isEmpty()) {
            throw new IllegalArgumentException("账号必须关联至少一个已启用科室，禁止使用任意科室名称");
        }
        if (!validDepartmentIds.contains(primaryDepartmentId)) {
            primaryDepartmentId = validDepartmentIds.stream().findFirst().orElse("");
        }

        ArrayNode normalizedIds = objectMapper.createArrayNode();
        validDepartmentIds.forEach(normalizedIds::add);
        account.set("departmentIds", normalizedIds);
        account.put("primaryDepartmentId", primaryDepartmentId);
        account.put("department", departmentNames.getOrDefault(primaryDepartmentId, ""));
        return account;
    }

    private void synchronizeAccountDepartments(ObjectNode account) {
        String accountId = text(account, "id");
        jdbcTemplate.update("DELETE FROM clinic_account_departments WHERE account_id = ?", accountId);
        String primaryDepartmentId = text(account, "primaryDepartmentId");
        JsonNode departmentIds = account.path("departmentIds");
        if (!departmentIds.isArray()) return;
        for (JsonNode departmentId : departmentIds) {
            jdbcTemplate.update("""
                INSERT INTO clinic_account_departments (
                  account_id, department_id, is_primary, status, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
                accountId,
                departmentId.asText(),
                primaryDepartmentId.equals(departmentId.asText())
            );
        }
    }

    private void revokeSessionsIfDisabled(ObjectNode account) {
        if ("启用".equals(text(account, "status", "启用"))) return;
        jdbcTemplate.update(
            """
            UPDATE clinic_auth_sessions
            SET revoked_at = CURRENT_TIMESTAMP(6), revoke_reason = 'account_disabled'
            WHERE user_id = ? AND revoked_at IS NULL
            """,
            text(account, "id")
        );
    }

    private void revokeSessionsForUnavailableAccounts() {
        jdbcTemplate.update("""
            UPDATE clinic_auth_sessions
            SET revoked_at = CURRENT_TIMESTAMP(6), revoke_reason = 'account_unavailable'
            WHERE revoked_at IS NULL
              AND user_id NOT IN (SELECT id FROM clinic_accounts WHERE status = '启用')
            """);
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

    public void ensureRevision() {
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

    public String currentRevision() {
        ensureRevision();
        return jdbcTemplate.queryForObject(
            "SELECT meta_value FROM clinic_db_meta WHERE meta_key = ?",
            String.class,
            REVISION_KEY
        );
    }

    public String updateRevision() {
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
