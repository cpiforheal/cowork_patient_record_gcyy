package com.coshare.patientrecord.medicalrecord.repository;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.medicalrecord.entity.GeneratedMedicalRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MedicalRecordVersionRepository {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MedicalRecordVersionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ObjectNode saveVersion(
        String patientId,
        String id,
        int version,
        Path filePath,
        String fileName,
        String hash,
        ObjectNode sourceSnapshot,
        SessionUser user,
        String status,
        String finalizedAt,
        String templateName,
        String templateVersion,
        String generatorName
    ) {
        String generatedAt = now();
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", id);
        row.put("patientId", patientId);
        row.put("version", version);
        row.put("status", status);
        row.put("content", "");
        row.put("contentHash", hash);
        row.put("model", generatorName);
        row.put("templateName", templateName);
        row.put("templateVersion", templateVersion);
        row.put("fileName", fileName);
        row.put("filePath", filePath.toString());
        row.put("downloadUrl", "/clinic-api/medical-record/download?id=" + id);
        row.put("operator", user.name());
        row.put("operatorRole", user.role());
        row.put("generatedAt", generatedAt);
        row.put("finalizedAt", finalizedAt);
        row.put("voidedAt", "");
        row.put("voidReason", "");
        row.set("sourceFieldSnapshot", sourceSnapshot);
        upsertRaw(row);
        return row;
    }

    public void upsertRaw(ObjectNode row) {
        jdbcTemplate.update("""
            INSERT INTO clinic_generated_medical_records (
              id, patient_id, version, status, content, content_hash, model, operator,
              operator_role, generated_at, finalized_at, voided_at, void_reason,
              source_snapshot, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              status = VALUES(status),
              content = VALUES(content),
              content_hash = VALUES(content_hash),
              model = VALUES(model),
              finalized_at = VALUES(finalized_at),
              voided_at = VALUES(voided_at),
              void_reason = VALUES(void_reason),
              source_snapshot = VALUES(source_snapshot),
              raw_json = VALUES(raw_json)
            """,
            text(row, "id"),
            text(row, "patientId"),
            row.path("version").asInt(1),
            text(row, "status"),
            text(row, "content"),
            text(row, "contentHash"),
            text(row, "model"),
            text(row, "operator"),
            text(row, "operatorRole"),
            text(row, "generatedAt"),
            text(row, "finalizedAt"),
            text(row, "voidedAt"),
            text(row, "voidReason"),
            toJson(row.path("sourceFieldSnapshot")),
            toJson(row)
        );
    }

    public void upsertRecordField(String patientId, String fieldKey, String value) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("value", value);
        jdbcTemplate.update("""
            INSERT INTO clinic_record_field_values (patient_id, field_key, field_value, raw_json)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              field_value = VALUES(field_value),
              raw_json = VALUES(raw_json)
            """,
            patientId,
            fieldKey,
            value,
            toJson(raw)
        );
        jdbcTemplate.update("""
            INSERT INTO clinic_record_fields (patient_id, fields_json)
            VALUES (?, JSON_OBJECT())
            ON DUPLICATE KEY UPDATE patient_id = VALUES(patient_id)
            """,
            patientId
        );
    }

    public void writeAudit(String patientId, SessionUser user, String action, String actionCode, String detail) {
        ObjectNode raw = objectMapper.createObjectNode();
        String id = "audit-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
        raw.put("id", id);
        raw.put("time", now());
        raw.put("operator", user.name());
        raw.put("role", user.roleLabel());
        raw.put("patientId", patientId);
        raw.put("module", "medical-record");
        raw.put("action", action);
        raw.put("actionCode", actionCode);
        raw.put("targetType", "medical-record");
        raw.put("targetKey", patientId);
        raw.put("result", "success");
        raw.put("detail", detail);
        jdbcTemplate.update("""
            INSERT INTO clinic_audit_logs (id, time, operator, role, patient, patient_id, module, action, result, raw_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            id,
            text(raw, "time"),
            user.name(),
            user.roleLabel(),
            "",
            patientId,
            "medical-record",
            action,
            "success",
            toJson(raw)
        );
    }

    public int deleteRecord(String id) {
        if (safe(id).isBlank()) throw new IllegalArgumentException("缺少目标病历版本ID");
        return jdbcTemplate.update("DELETE FROM clinic_generated_medical_records WHERE id = ?", id);
    }

    public ObjectNode loadRecord(String id) {
        if (safe(id).isBlank()) throw new IllegalArgumentException("缺少目标病历版本ID");
        java.util.List<ObjectNode> rows = jdbcTemplate.query(
            """
                SELECT id, patient_id, version, status, content, content_hash, model, operator,
                       operator_role, generated_at, finalized_at, voided_at, void_reason, raw_json
                FROM clinic_generated_medical_records WHERE id = ? LIMIT 1
                """,
            (rs, rowNum) -> readStoredRecord(rs),
            id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ArrayNode versionsNode(String patientId, String templateVersion, String generatorName) {
        return versionsNode(patientId, templateVersion, generatorName, 0);
    }

    public ArrayNode versionsNode(String patientId, String templateVersion, String generatorName, int limit) {
        ArrayNode rows = objectMapper.createArrayNode();
        String sql = """
                SELECT id, patient_id, version, status, content, content_hash, model, operator,
                       operator_role, generated_at, finalized_at, voided_at, void_reason, raw_json
                FROM clinic_generated_medical_records
                WHERE patient_id = ?
                ORDER BY version DESC, generated_at DESC
                """;
        org.springframework.jdbc.core.RowCallbackHandler handler = resultSet -> rows.add(readStoredRecord(resultSet, templateVersion, generatorName));
        if (limit > 0) {
            jdbcTemplate.query(sql + " LIMIT ?", handler, patientId, Math.min(limit, 200));
        } else {
            jdbcTemplate.query(sql, handler, patientId);
        }
        return rows;
    }

    public int nextVersion(String patientId) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM clinic_generated_medical_records WHERE patient_id = ?",
            Integer.class,
            patientId
        );
        return value == null ? 1 : value;
    }

    private ObjectNode readStoredRecord(ResultSet resultSet) throws java.sql.SQLException {
        return readStoredRecord(toEntity(resultSet), "", "");
    }

    private ObjectNode readStoredRecord(ResultSet resultSet, String templateVersion, String generatorName) throws java.sql.SQLException {
        return readStoredRecord(toEntity(resultSet), templateVersion, generatorName);
    }

    private ObjectNode readStoredRecord(GeneratedMedicalRecord record, String templateVersion, String generatorName) {
        ObjectNode row = readObject(record.rawJson());
        putIfMissing(row, "id", record.id());
        putIfMissing(row, "patientId", record.patientId());
        if (!row.has("version") || row.path("version").asText("").isBlank()) row.put("version", record.version());
        putIfMissing(row, "status", record.status());
        putIfMissing(row, "content", record.content());
        putIfMissing(row, "contentHash", record.contentHash());
        putIfMissing(row, "model", record.model());
        putIfMissing(row, "operator", record.operator());
        putIfMissing(row, "operatorRole", record.operatorRole());
        putIfMissing(row, "generatedAt", record.generatedAt());
        putIfMissing(row, "finalizedAt", record.finalizedAt());
        putIfMissing(row, "voidedAt", record.voidedAt());
        putIfMissing(row, "voidReason", record.voidReason());
        if (!row.has("fileName")) row.put("fileName", "医生目标病历-V" + row.path("version").asInt(1) + ".docx");
        if (!row.has("downloadUrl")) row.put("downloadUrl", "/clinic-api/medical-record/download?id=" + text(row, "id"));
        if (!row.has("templateVersion")) {
            row.put("templateVersion", generatorName.equals(text(row, "model")) ? templateVersion : "legacy-text");
        }
        return row;
    }

    private GeneratedMedicalRecord toEntity(ResultSet resultSet) throws java.sql.SQLException {
        return new GeneratedMedicalRecord(
            resultSet.getString("id"),
            resultSet.getString("patient_id"),
            resultSet.getInt("version"),
            resultSet.getString("status"),
            resultSet.getString("content"),
            resultSet.getString("content_hash"),
            resultSet.getString("model"),
            resultSet.getString("operator"),
            resultSet.getString("operator_role"),
            resultSet.getString("generated_at"),
            resultSet.getString("finalized_at"),
            resultSet.getString("voided_at"),
            resultSet.getString("void_reason"),
            resultSet.getString("raw_json")
        );
    }

    private void putIfMissing(ObjectNode row, String key, String value) {
        if (!row.has(key) || row.path(key).asText("").isBlank()) row.put(key, safe(value));
    }

    private ObjectNode readObject(String rawJson) {
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception error) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("legacyParseWarning", "目标病历历史版本 JSON 无法解析，已按数据库列降级读取");
            return fallback;
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
        return node == null ? "" : node.path(key).asText("");
    }

    private static String safe(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }
}
