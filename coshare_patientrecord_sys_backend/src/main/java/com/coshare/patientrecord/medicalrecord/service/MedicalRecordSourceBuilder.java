package com.coshare.patientrecord.medicalrecord.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.medicalrecord.model.TargetField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("mysql")
public class MedicalRecordSourceBuilder {

    private static final Pattern MOBILE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{6}(?:19|20)\\d{2}\\d{7}[0-9Xx]");

    private final ObjectMapper objectMapper;
    private final ClinicDatabaseService databaseService;
    private final JdbcTemplate jdbcTemplate;

    public MedicalRecordSourceBuilder(ObjectMapper objectMapper, ClinicDatabaseService databaseService, JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ObjectNode readPatientSource(
        String patientId,
        SessionUser user,
        boolean maskSensitive,
        String templateName,
        String templateVersion
    ) {
        ObjectNode db = databaseService.readDbForUser(user);
        JsonNode patient = findPatient(db.path("patients"), patientId);
        if (patient == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在或当前账号无权查看");
        return buildSourceSnapshot(patient, db.path("records").path(patientId), db.path("documents").path(patientId), maskSensitive, templateName, templateVersion);
    }

    public void assertCanReadPatient(String patientId) {
        if (safe(patientId).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_patients WHERE id = ?", Integer.class, patientId);
        if (count == null || count <= 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在或当前账号无权查看");
    }

    public ArrayNode missingItems(ObjectNode sourceSnapshot, List<TargetField> targetFields) {
        JsonNode fields = sourceSnapshot.path("recordFields");
        ArrayNode missing = objectMapper.createArrayNode();
        for (TargetField field : targetFields) {
            if (!field.required()) continue;
            String value = fieldValue(sourceSnapshot.path("patient"), fields, field);
            if (isIncomplete(value)) {
                missing.add(field.section() + " - " + field.label());
            }
        }
        return missing;
    }

    public Map<String, String> buildTemplateValues(ObjectNode sourceSnapshot, List<TargetField> targetFields) {
        JsonNode patient = sourceSnapshot.path("patient");
        JsonNode record = sourceSnapshot.path("recordFields");
        Map<String, String> values = new LinkedHashMap<>();
        for (TargetField field : targetFields) {
            String value = fieldValue(patient, record, field);
            if ("age".equals(field.key())) value = normalizeAge(value);
            put(values, field.key(), value);
            for (String anchor : field.anchors()) {
                put(values, anchor, value);
            }
        }
        put(values, "recordDate", firstNonBlank(record, "recordDate", "historyCollectedAt", LocalDate.now().toString()));
        put(values, "missingItems", joinArray(missingItems(sourceSnapshot, targetFields)));
        return values;
    }

    private ObjectNode buildSourceSnapshot(
        JsonNode patient,
        JsonNode record,
        JsonNode documents,
        boolean maskSensitive,
        String templateName,
        String templateVersion
    ) {
        ObjectNode source = objectMapper.createObjectNode();
        source.set("patient", maskSensitive ? desensitize(patient) : safeObject(patient));
        source.set("recordFields", compactFields(record, maskSensitive));
        source.set("attachments", compactDocuments(documents, maskSensitive));
        source.put("generatedFor", templateName);
        source.put("templateVersion", templateVersion);
        return source;
    }

    private JsonNode safeObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return objectMapper.createObjectNode();
        return node.deepCopy();
    }

    private ObjectNode compactFields(JsonNode record, boolean maskSensitive) {
        ObjectNode fields = objectMapper.createObjectNode();
        if (record == null || !record.isObject()) return fields;
        java.util.Iterator<Map.Entry<String, JsonNode>> iterator = record.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            String value = entry.getValue().asText("");
            if (maskSensitive) value = maskSensitive(value);
            if (!value.isBlank()) fields.put(entry.getKey(), value.length() > 2000 ? value.substring(0, 2000) + "..." : value);
        }
        return fields;
    }

    private ArrayNode compactDocuments(JsonNode documents, boolean maskSensitive) {
        ArrayNode rows = objectMapper.createArrayNode();
        if (documents == null || !documents.isArray()) return rows;
        for (JsonNode document : documents) {
            ObjectNode row = rows.addObject();
            row.put("fileName", maskSensitive ? maskSensitive(text(document, "fileName")) : text(document, "fileName"));
            row.put("fieldLabel", text(document, "fieldLabel"));
            row.put("department", text(document, "department"));
            row.put("uploadedAt", text(document, "uploadedAt"));
        }
        return rows;
    }

    private JsonNode desensitize(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return objectMapper.createObjectNode();
        JsonNode copy = node.deepCopy();
        if (copy.isObject()) {
            ObjectNode object = (ObjectNode) copy;
            List.of("name", "phone", "address", "contactName", "contactPhone", "contactAddress").forEach(key -> {
                if (object.has(key)) object.put(key, maskSensitive(object.path(key).asText("")));
            });
        }
        return copy;
    }

    private JsonNode findPatient(JsonNode patients, String patientId) {
        if (patients == null || !patients.isArray()) return null;
        for (JsonNode patient : patients) {
            if (patientId.equals(text(patient, "id"))) return patient;
        }
        return null;
    }

    private String maskSensitive(String value) {
        String text = safe(value);
        if (text.isBlank()) return "";
        text = MOBILE_PATTERN.matcher(text).replaceAll(match -> {
            String raw = match.group();
            return raw.substring(0, 3) + "****" + raw.substring(7);
        });
        text = ID_CARD_PATTERN.matcher(text).replaceAll(match -> {
            String raw = match.group();
            return raw.substring(0, 6) + "********" + raw.substring(raw.length() - 4);
        });
        return text;
    }

    private void put(Map<String, String> values, String key, String value) {
        values.put(key, value == null || value.isBlank() ? "待补充" : value);
    }

    private String fieldValue(JsonNode patient, JsonNode record, TargetField field) {
        String direct = record.path(field.key()).asText("");
        if (!direct.isBlank()) return direct;
        for (String source : field.sources()) {
            String value = source.startsWith("patient.")
                ? patient.path(source.substring("patient.".length())).asText("")
                : record.path(source).asText("");
            if (!value.isBlank()) return value;
        }
        return field.defaultValue();
    }

    private String firstNonBlank(JsonNode node, String first, String fallback) {
        String firstValue = node.path(first).asText("");
        return firstValue.isBlank() ? fallback : firstValue;
    }

    private String firstNonBlank(JsonNode node, String first, String second, String fallback) {
        String firstValue = node.path(first).asText("");
        if (!firstValue.isBlank()) return firstValue;
        String secondValue = node.path(second).asText("");
        return secondValue.isBlank() ? fallback : secondValue;
    }

    private String normalizeAge(String age) {
        String value = safe(age);
        if (value.isBlank()) return "待补充";
        return value.endsWith("岁") ? value : value + "岁";
    }

    private boolean isIncomplete(String value) {
        String text = safe(value);
        return text.isBlank()
            || "待补充".equals(text)
            || "未见记录".equals(text)
            || text.contains("____")
            || text.contains("________");
    }

    private String joinArray(ArrayNode array) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : array) {
            if (!builder.isEmpty()) builder.append("、");
            builder.append(item.asText());
        }
        return builder.isEmpty() ? "无明显缺失项" : builder.toString();
    }

    private String text(JsonNode node, String key) {
        return node == null ? "" : node.path(key).asText("");
    }

    private static String safe(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }
}
