package com.coshare.patientrecord.medicalrecord.service;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.clinic.service.ClinicDatabaseService;
import com.coshare.patientrecord.common.privacy.SensitiveDataMasker;
import com.coshare.patientrecord.medicalrecord.model.TargetField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("mysql")
public class MedicalRecordSourceBuilder {

    private final ObjectMapper objectMapper;
    private final ClinicDatabaseService databaseService;
    private final JdbcTemplate jdbcTemplate;
    private final SensitiveDataMasker sensitiveDataMasker;

    public MedicalRecordSourceBuilder(
        ObjectMapper objectMapper,
        ClinicDatabaseService databaseService,
        JdbcTemplate jdbcTemplate,
        SensitiveDataMasker sensitiveDataMasker
    ) {
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.jdbcTemplate = jdbcTemplate;
        this.sensitiveDataMasker = sensitiveDataMasker;
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
        source.set("patient", maskSensitive ? sensitiveDataMasker.maskJson(patient) : safeObject(patient));
        source.set("recordFields", compactFields(record, maskSensitive));
        source.set("attachments", compactDocuments(documents, maskSensitive));
        source.put("generatedFor", templateName);
        source.put("templateVersion", templateVersion);
        if (maskSensitive) source.put("desensitizationPolicyVersion", sensitiveDataMasker.policyVersion());
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
            if (maskSensitive) value = sensitiveDataMasker.maskFieldValue(entry.getKey(), value);
            if (!value.isBlank()) fields.put(entry.getKey(), value.length() > 2000 ? value.substring(0, 2000) + "..." : value);
        }
        return fields;
    }

    private ArrayNode compactDocuments(JsonNode documents, boolean maskSensitive) {
        ArrayNode rows = objectMapper.createArrayNode();
        if (documents == null || !documents.isArray()) return rows;
        for (JsonNode document : documents) {
            ObjectNode row = rows.addObject();
            row.put("fileName", maskSensitive ? sensitiveDataMasker.maskFieldValue("fileName", text(document, "fileName")) : text(document, "fileName"));
            row.put("fieldLabel", text(document, "fieldLabel"));
            row.put("department", text(document, "department"));
            row.put("uploadedAt", text(document, "uploadedAt"));
        }
        return rows;
    }

    private JsonNode findPatient(JsonNode patients, String patientId) {
        if (patients == null || !patients.isArray()) return null;
        for (JsonNode patient : patients) {
            if (patientId.equals(text(patient, "id"))) return patient;
        }
        return null;
    }

    private void put(Map<String, String> values, String key, String value) {
        values.put(key, value == null ? "" : value.trim());
    }

    private String fieldValue(JsonNode patient, JsonNode record, TargetField field) {
        String direct = record.path(field.key()).asText("");
        if (!isIncomplete(direct)) return direct;
        if ("auxiliaryExamSummary".equals(field.key())) {
            String synthesized = synthesizeAuxiliaryExamSummary(record);
            if (!synthesized.isBlank()) return synthesized;
        }
        for (String source : field.sources()) {
            String value = source.startsWith("patient.")
                ? patient.path(source.substring("patient.".length())).asText("")
                : record.path(source).asText("");
            if (!isIncomplete(value)) return value;
        }
        return field.defaultValue();
    }

    private String synthesizeAuxiliaryExamSummary(JsonNode record) {
        List<String> summaries = new ArrayList<>();
        addMetricSummary(
            summaries,
            "血常规",
            record,
            List.of(
                metric("bloodWbc", "WBC", "10^9/L"),
                metric("bloodNeuPercent", "NeU%", "%"),
                metric("bloodLymPercent", "Lym%", "%"),
                metric("bloodMonPercent", "Mon%", "%"),
                metric("bloodRbc", "RBC", "10^12/L"),
                metric("bloodHgb", "HGB", "g/L"),
                metric("bloodPlt", "PLT", "10^9/L"),
                metric("lab_bloodRoutine_neuCount", "NeU#", "10^9/L"),
                metric("lab_bloodRoutine_lymCount", "Lym#", "10^9/L"),
                metric("lab_bloodRoutine_monCount", "Mon#", "10^9/L")
            ),
            "bloodRoutine"
        );
        addMetricSummary(
            summaries,
            "CRP/SAA",
            record,
            List.of(metric("lab_crpSaa_crp", "CRP", "mg/L"), metric("lab_crpSaa_saa", "SAA", "mg/L")),
            "crpStatus"
        );
        addMetricSummary(
            summaries,
            "餐后血糖",
            record,
            List.of(metric("postprandialGlucose", "2hPG", "mmol/L")),
            "postprandialGlucose"
        );
        addMetricSummary(
            summaries,
            "生化肝肾功",
            record,
            List.of(
                metric("lab_biochemistry_glu", "Glu", "mmol/L"),
                metric("lab_biochemistry_alt", "ALT", "U/L"),
                metric("lab_biochemistry_ast", "AST", "U/L"),
                metric("lab_biochemistry_crea", "CREA", "umol/L"),
                metric("lab_biochemistry_ua", "UA", "umol/L"),
                metric("lab_biochemistry_urea", "UREA", "mmol/L")
            ),
            "biochemistry"
        );
        addMetricSummary(
            summaries,
            "尿常规",
            record,
            List.of(
                metric("lab_urineRoutine_wbc", "LEU", ""),
                metric("lab_urineRoutine_nit", "NIT", ""),
                metric("lab_urineRoutine_pro", "PRO", ""),
                metric("lab_urineRoutine_bld", "BLD", ""),
                metric("lab_urineRoutine_glu", "GLU", "")
            ),
            "urineRoutine"
        );
        addMetricSummary(
            summaries,
            "术前筛查",
            record,
            List.of(
                metric("lab_hbvFive_hbsag", "HBsAg", ""),
                metric("lab_hbvFive_hbsab", "HBsAb", ""),
                metric("lab_infectious_hiv", "HIV", ""),
                metric("lab_infectious_tppa", "TPPA", ""),
                metric("lab_infectious_hcv", "HCV", "")
            ),
            "preOpEight"
        );
        addMetricSummary(
            summaries,
            "糖化血红蛋白",
            record,
            List.of(metric("lab_hba1c_hba1c", "HbA1c", "%")),
            ""
        );
        String ecg = firstClean(record, "ecgResult", "ecgStatus");
        if (!ecg.isBlank()) summaries.add("心电图：" + ecg);
        return String.join("；", summaries);
    }

    private Metric metric(String key, String label, String unit) {
        return new Metric(key, label, unit);
    }

    private void addMetricSummary(List<String> summaries, String title, JsonNode record, List<Metric> metrics, String fallbackKey) {
        List<String> filled = new ArrayList<>();
        for (Metric metric : metrics) {
            String value = cleanValue(record.path(metric.key()).asText(""));
            if (!value.isBlank()) filled.add(metric.label() + " " + value + metric.unit());
        }
        if (!filled.isEmpty()) {
            summaries.add(title + "：" + String.join("，", filled));
            return;
        }
        if (!fallbackKey.isBlank()) {
            String fallback = cleanValue(record.path(fallbackKey).asText(""));
            if (!fallback.isBlank()) summaries.add(fallback);
        }
    }

    private String firstClean(JsonNode record, String... keys) {
        for (String key : keys) {
            String value = cleanValue(record.path(key).asText(""));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String cleanValue(String value) {
        String text = safe(value);
        return isIncomplete(text) ? "" : text;
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

    private record Metric(String key, String label, String unit) {}
}
