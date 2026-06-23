package com.example.coshare_patientrecord_sys;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class ClinicMedicalRecordService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String TEMPLATE_NAME = "医生目标病历模板 v1";
    private static final String TEMPLATE_VERSION = "leader-template-20260623";
    private static final String TEMPLATE_RESOURCE = "medical-record-templates/target-medical-record-template.docx";
    private static final String TEMPLATE_SOURCE_NAME = "周xx病历模版.docx";
    private static final String GENERATOR_NAME = "docx-template";
    private static final String DISCLAIMER = "本目标病历由医生填写的动态字段按固定 docx 模板生成；模板固定文字、格式和页眉页脚不由系统改写。定稿后不可直接修改，只能作废或重新生成新版本。";
    private static final Pattern MOBILE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{6}(?:19|20)\\d{2}\\d{7}[0-9Xx]");
    private static final List<TargetField> TARGET_FIELDS = buildTargetFields();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ClinicDatabaseService databaseService;
    private final Path generatedDir;

    public ClinicMedicalRecordService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ClinicDatabaseService databaseService,
        @Value("${clinic.generated-medical-record-dir:${clinic.attachment-dir}/../generated-medical-records}") String generatedDir
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.generatedDir = Path.of(generatedDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_generated_medical_records (
              id VARCHAR(128) PRIMARY KEY,
              patient_id VARCHAR(64) NOT NULL,
              version INT NOT NULL,
              status VARCHAR(32) NOT NULL,
              content MEDIUMTEXT NOT NULL,
              content_hash VARCHAR(128),
              model VARCHAR(128),
              operator VARCHAR(100),
              operator_role VARCHAR(64),
              generated_at VARCHAR(32),
              finalized_at VARCHAR(32),
              voided_at VARCHAR(32),
              void_reason VARCHAR(500),
              source_snapshot JSON NOT NULL,
              raw_json JSON NOT NULL,
              INDEX idx_medical_records_patient (patient_id),
              INDEX idx_medical_records_status (status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }

    public Map<String, Object> templateStatus(AuthSessionService.SessionUser user) {
        requireReadRole();
        ArrayNode unboundFields = unboundTemplateFields();
        ObjectNode status = objectMapper.createObjectNode();
        status.put("name", TEMPLATE_NAME);
        status.put("templateVersion", TEMPLATE_VERSION);
        status.put("configured", templateAvailable() && unboundFields.isEmpty());
        status.put("promptConfigurable", false);
        status.put("templateSource", TEMPLATE_SOURCE_NAME);
        status.put("commandSource", "固定 docx 母版 + 医生动态字段填充；本轮不调用 AI");
        status.put("disclaimer", DISCLAIMER);
        status.put("generatedDir", generatedDir.toString());
        ArrayNode sections = status.putArray("sections");
        targetSectionNames().forEach(sections::add);
        ArrayNode required = status.putArray("requiredFields");
        TARGET_FIELDS.stream().filter(TargetField::required).forEach(field -> {
            ObjectNode row = required.addObject();
            row.put("key", field.key());
            row.put("label", field.label());
            row.put("section", field.section());
        });
        status.set("fieldMatrix", targetFieldMatrix());
        status.set("unboundFields", unboundFields);
        return objectMapper.convertValue(status, new TypeReference<Map<String, Object>>() {});
    }

    public Map<String, Object> precheck(GenerateRequest request, AuthSessionService.SessionUser user) {
        requireReadRole();
        String patientId = safe(request == null ? "" : request.patientId());
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        ObjectNode source = readPatientSource(patientId, user, false);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode unboundFields = unboundTemplateFields();
        result.set("missingItems", missingItems(source));
        result.set("unboundFields", unboundFields);
        result.set("fieldMatrix", targetFieldMatrix());
        result.put("ready", result.path("missingItems").isArray() && result.path("missingItems").isEmpty() && unboundFields.isEmpty());
        result.put("disclaimer", DISCLAIMER);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> saveWorkspace(WorkspaceSaveRequest request, AuthSessionService.SessionUser user) {
        requireGenerateRole();
        String patientId = safe(request == null ? "" : request.patientId());
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        assertCanReadPatient(patientId, user);
        JsonNode values = request == null || request.values() == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(request.values());
        if (!values.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标病历字段必须是对象");

        Set<String> allowedKeys = targetFieldKeys();
        ObjectNode accepted = objectMapper.createObjectNode();
        values.fields().forEachRemaining(entry -> {
            if (allowedKeys.contains(entry.getKey())) {
                accepted.put(entry.getKey(), safe(entry.getValue().asText("")));
            }
        });
        if (accepted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可保存的目标病历字段");
        }

        accepted.fields().forEachRemaining(entry -> upsertRecordField(patientId, entry.getKey(), entry.getValue().asText("")));
        writeAudit(patientId, user, "保存医生目标病历填写", "medical-record.workspace.save", "保存 " + accepted.size() + " 个目标病历动态字段");

        ObjectNode source = readPatientSource(patientId, user, false);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("values", accepted);
        result.set("missingItems", missingItems(source));
        result.put("disclaimer", DISCLAIMER);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    public Map<String, Object> versions(String patientId, AuthSessionService.SessionUser user) {
        requireReadRole();
        String safePatientId = safe(patientId);
        if (safePatientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        assertCanReadPatient(safePatientId, user);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("versions", versionsNode(safePatientId));
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> generate(GenerateRequest request, AuthSessionService.SessionUser user) {
        requireGenerateRole();
        String patientId = safe(request == null ? "" : request.patientId());
        if (patientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        if (!templateAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "医生目标病历模板未配置，请检查后端模板文件");
        }
        ArrayNode unboundFields = unboundTemplateFields();
        if (!unboundFields.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "目标病历模板仍有动态字段未绑定占位符：" + joinArray(unboundFields));
        }

        ObjectNode sourceSnapshot = readPatientSource(patientId, user, false);
        ObjectNode logSnapshot = readPatientSource(patientId, user, true);
        ArrayNode missingItems = missingItems(sourceSnapshot);
        if (!missingItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标病历动态字段未补齐：" + joinArray(missingItems));
        }
        JsonNode patient = sourceSnapshot.path("patient");
        JsonNode record = sourceSnapshot.path("recordFields");
        Map<String, String> replacements = buildTemplateValues(patient, record, sourceSnapshot);

        int version = nextVersion(patientId);
        String id = "medrec-" + UUID.randomUUID();
        byte[] generatedBytes = renderTemplate(replacements);
        String fileName = sanitizeFileName(patient.path("name").asText("patient")) + "-医生目标病历-V" + version + ".docx";
        Path target = writeGeneratedFile(patientId, id, fileName, generatedBytes);
        String hash = sha256(generatedBytes);
        ObjectNode created = saveVersion(patientId, id, version, target, fileName, hash, logSnapshot, user, "draft", "");

        ObjectNode result = objectMapper.createObjectNode();
        result.set("record", created);
        result.set("missingItems", missingItems);
        result.put("disclaimer", DISCLAIMER);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> finalizeRecord(FinalizeRequest request, AuthSessionService.SessionUser user) {
        requireFinalizeRole();
        String id = safe(request == null ? "" : request.id());
        if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少目标病历版本ID");
        ObjectNode row = loadRecord(id);
        assertCanReadPatient(text(row, "patientId"), user);
        if (!"draft".equals(text(row, "status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有草稿版本可以确认定稿");
        }
        row.put("status", "finalized");
        row.put("finalizedAt", now());
        upsertRaw(row);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("record", row);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    @Transactional
    public Map<String, Object> voidRecord(VoidRequest request, AuthSessionService.SessionUser user) {
        requireFinalizeRole();
        String id = safe(request == null ? "" : request.id());
        if (id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少目标病历版本ID");
        ObjectNode row = loadRecord(id);
        assertCanReadPatient(text(row, "patientId"), user);
        if ("voided".equals(text(row, "status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该版本已作废");
        }
        row.put("status", "voided");
        row.put("voidedAt", now());
        row.put("voidReason", safe(request == null ? "" : request.reason()));
        upsertRaw(row);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("record", row);
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    public DownloadFile download(String id, AuthSessionService.SessionUser user) {
        requireReadRole();
        ObjectNode row = loadRecord(safe(id));
        assertCanReadPatient(text(row, "patientId"), user);
        String filePath = text(row, "filePath");
        if (filePath.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该目标病历暂无可下载文件");
        Path target = Path.of(filePath).toAbsolutePath().normalize();
        if (!target.startsWith(generatedDir) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标病历文件不存在，请重新生成");
        }
        return new DownloadFile(new FileSystemResource(target), text(row, "fileName", "医生目标病历.docx"));
    }

    private ObjectNode buildSourceSnapshot(JsonNode patient, JsonNode record, JsonNode documents, boolean maskSensitive) {
        ObjectNode source = objectMapper.createObjectNode();
        source.set("patient", maskSensitive ? desensitize(patient) : safeObject(patient));
        source.set("recordFields", compactFields(record, maskSensitive));
        source.set("attachments", compactDocuments(documents, maskSensitive));
        source.put("generatedFor", TEMPLATE_NAME);
        source.put("templateVersion", TEMPLATE_VERSION);
        return source;
    }

    private JsonNode safeObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return objectMapper.createObjectNode();
        return node.deepCopy();
    }

    private ObjectNode compactFields(JsonNode record, boolean maskSensitive) {
        ObjectNode fields = objectMapper.createObjectNode();
        if (record == null || !record.isObject()) return fields;
        Iterator<Map.Entry<String, JsonNode>> iterator = record.fields();
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

    private ObjectNode readPatientSource(String patientId, AuthSessionService.SessionUser user, boolean maskSensitive) {
        ObjectNode db = databaseService.readDbForUser(user);
        JsonNode patient = findPatient(db.path("patients"), patientId);
        if (patient == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在或当前账号无权查看");
        return buildSourceSnapshot(patient, db.path("records").path(patientId), db.path("documents").path(patientId), maskSensitive);
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

    private ArrayNode missingItems(ObjectNode sourceSnapshot) {
        JsonNode fields = sourceSnapshot.path("recordFields");
        ArrayNode missing = objectMapper.createArrayNode();
        for (TargetField field : TARGET_FIELDS) {
            if (!field.required()) continue;
            String value = fieldValue(sourceSnapshot.path("patient"), fields, field);
            if (isIncomplete(value)) {
                missing.add(field.section() + " - " + field.label());
            }
        }
        return missing;
    }

    private Map<String, String> buildTemplateValues(JsonNode patient, JsonNode record, ObjectNode sourceSnapshot) {
        Map<String, String> values = new LinkedHashMap<>();
        for (TargetField field : TARGET_FIELDS) {
            String value = fieldValue(patient, record, field);
            if ("age".equals(field.key())) value = normalizeAge(value);
            put(values, field.key(), value);
            for (String anchor : field.anchors()) {
                put(values, anchor, value);
            }
        }
        put(values, "recordDate", firstNonBlank(record, "recordDate", "historyCollectedAt", LocalDate.now().toString()));
        put(values, "missingItems", joinArray(missingItems(sourceSnapshot)));
        return values;
    }

    private void put(Map<String, String> values, String key, String value) {
        values.put(key, value == null || value.isBlank() ? "待补充" : value);
    }

    private byte[] renderTemplate(Map<String, String> replacements) {
        try (InputStream inputStream = templateInputStream();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                ZipEntry next = new ZipEntry(entry.getName());
                zipOutputStream.putNextEntry(next);
                byte[] bytes = zipInputStream.readAllBytes();
                if (entry.getName().matches("word/(document|header\\d*|footer\\d*)\\.xml")) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    xml = applyReplacements(xml, replacements);
                    bytes = xml.getBytes(StandardCharsets.UTF_8);
                }
                zipOutputStream.write(bytes);
                zipOutputStream.closeEntry();
                zipInputStream.closeEntry();
            }
            zipOutputStream.finish();
            return output.toByteArray();
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "医生目标病历模板生成失败：" + error.getMessage(), error);
        }
    }

    private String applyReplacements(String xml, Map<String, String> replacements) {
        String result = xml;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String key = entry.getKey();
            String value = xmlEscape(entry.getValue());
            result = result.replace("${" + key + "}", value);
        }
        return result;
    }

    private InputStream templateInputStream() throws IOException {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(TEMPLATE_RESOURCE);
        if (stream == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未找到医生目标病历模板：" + TEMPLATE_RESOURCE);
        }
        return stream;
    }

    private boolean templateAvailable() {
        return getClass().getClassLoader().getResource(TEMPLATE_RESOURCE) != null;
    }

    private Set<String> templatePlaceholderKeys() {
        Set<String> keys = new HashSet<>();
        if (!templateAvailable()) return keys;
        try (InputStream inputStream = templateInputStream();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            Pattern placeholder = Pattern.compile("\\$\\{([^}]+)}");
            while ((entry = zipInputStream.getNextEntry()) != null) {
                byte[] bytes = zipInputStream.readAllBytes();
                if (entry.getName().matches("word/(document|header\\d*|footer\\d*)\\.xml")) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    var matcher = placeholder.matcher(xml);
                    while (matcher.find()) keys.add(matcher.group(1));
                }
                zipInputStream.closeEntry();
            }
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "医生目标病历模板无法读取：" + error.getMessage(), error);
        }
        return keys;
    }

    private ArrayNode unboundTemplateFields() {
        ArrayNode rows = objectMapper.createArrayNode();
        Set<String> placeholders = templatePlaceholderKeys();
        for (TargetField field : TARGET_FIELDS) {
            if (!placeholders.contains(field.key())) {
                rows.add(field.section() + " - " + field.label() + "（" + field.key() + "）");
            }
        }
        return rows;
    }

    private Path writeGeneratedFile(String patientId, String id, String fileName, byte[] bytes) {
        try {
            Path dir = generatedDir.resolve(sanitizeFileName(patientId)).normalize();
            if (!dir.startsWith(generatedDir)) throw new IOException("invalid patient directory");
            Files.createDirectories(dir);
            Path target = dir.resolve(id + "-" + sanitizeFileName(fileName)).normalize();
            if (!target.startsWith(generatedDir)) throw new IOException("invalid target file");
            Files.write(target, bytes);
            return target;
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "目标病历文件写入失败：" + error.getMessage(), error);
        }
    }

    private ObjectNode saveVersion(
        String patientId,
        String id,
        int version,
        Path filePath,
        String fileName,
        String hash,
        ObjectNode sourceSnapshot,
        AuthSessionService.SessionUser user,
        String status,
        String finalizedAt
    ) {
        String generatedAt = now();
        ObjectNode row = objectMapper.createObjectNode();
        row.put("id", id);
        row.put("patientId", patientId);
        row.put("version", version);
        row.put("status", status);
        row.put("content", "");
        row.put("contentHash", hash);
        row.put("model", GENERATOR_NAME);
        row.put("templateName", TEMPLATE_NAME);
        row.put("templateVersion", TEMPLATE_VERSION);
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

    private void upsertRaw(ObjectNode row) {
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

    private void upsertRecordField(String patientId, String fieldKey, String value) {
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

    private void writeAudit(String patientId, AuthSessionService.SessionUser user, String action, String actionCode, String detail) {
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

    private ObjectNode loadRecord(String id) {
        if (safe(id).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少目标病历版本ID");
        List<ObjectNode> rows = jdbcTemplate.query(
            """
                SELECT id, patient_id, version, status, content, content_hash, model, operator,
                       operator_role, generated_at, finalized_at, voided_at, void_reason, raw_json
                FROM clinic_generated_medical_records WHERE id = ? LIMIT 1
                """,
            (rs, rowNum) -> readStoredRecord(rs),
            id
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标病历版本不存在");
        return rows.get(0);
    }

    private ArrayNode versionsNode(String patientId) {
        ArrayNode rows = objectMapper.createArrayNode();
        jdbcTemplate.query(
            """
                SELECT id, patient_id, version, status, content, content_hash, model, operator,
                       operator_role, generated_at, finalized_at, voided_at, void_reason, raw_json
                FROM clinic_generated_medical_records
                WHERE patient_id = ?
                ORDER BY version DESC, generated_at DESC
                """,
            (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> rows.add(readStoredRecord(resultSet)),
            patientId
        );
        return rows;
    }

    private int nextVersion(String patientId) {
        Integer value = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM clinic_generated_medical_records WHERE patient_id = ?",
            Integer.class,
            patientId
        );
        return value == null ? 1 : value;
    }

    private ArrayNode targetFieldMatrix() {
        ArrayNode sections = objectMapper.createArrayNode();
        Map<String, ArrayNode> fieldsBySection = new LinkedHashMap<>();
        for (TargetField field : TARGET_FIELDS) {
            fieldsBySection.computeIfAbsent(field.section(), ignored -> objectMapper.createArrayNode()).add(targetFieldNode(field));
        }
        fieldsBySection.forEach((sectionName, fields) -> {
            ObjectNode section = sections.addObject();
            section.put("section", sectionName);
            section.set("fields", fields);
        });
        return sections;
    }

    private ObjectNode targetFieldNode(TargetField field) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("key", field.key());
        row.put("label", field.label());
        row.put("section", field.section());
        row.put("kind", field.kind());
        row.put("required", field.required());
        row.put("aiPolishable", field.aiPolishable());
        row.put("placeholder", field.placeholder());
        ArrayNode sources = row.putArray("sources");
        field.sources().forEach(sources::add);
        return row;
    }

    private List<String> targetSectionNames() {
        List<String> names = new ArrayList<>();
        for (TargetField field : TARGET_FIELDS) {
            if (!names.contains(field.section())) names.add(field.section());
        }
        return names;
    }

    private Set<String> targetFieldKeys() {
        Set<String> keys = new HashSet<>();
        TARGET_FIELDS.forEach(field -> keys.add(field.key()));
        return keys;
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

    private void assertCanReadPatient(String patientId, AuthSessionService.SessionUser user) {
        if (safe(patientId).isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少患者ID");
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_patients WHERE id = ?", Integer.class, patientId);
        if (count == null || count <= 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "患者不存在或当前账号无权查看");
    }

    private JsonNode findPatient(JsonNode patients, String patientId) {
        if (patients == null || !patients.isArray()) return null;
        for (JsonNode patient : patients) {
            if (patientId.equals(text(patient, "id"))) return patient;
        }
        return null;
    }

    private void requireReadRole() {
        AuthPermission.requireAnyRole("当前账号无权查看目标病历", "admin", "doctor", "quality", "nurse");
    }

    private void requireGenerateRole() {
        AuthPermission.requireAnyRole("当前账号无权生成目标病历", "admin", "doctor");
    }

    private void requireFinalizeRole() {
        AuthPermission.requireAnyRole("当前账号无权定稿目标病历", "admin", "doctor");
    }

    private String joinArray(ArrayNode array) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : array) {
            if (!builder.isEmpty()) builder.append("、");
            builder.append(item.asText());
        }
        return builder.isEmpty() ? "无明显缺失项" : builder.toString();
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            return "";
        }
    }

    private ObjectNode readStoredRecord(ResultSet resultSet) throws java.sql.SQLException {
        ObjectNode row = readObject(resultSet.getString("raw_json"));
        putIfMissing(row, "id", resultSet.getString("id"));
        putIfMissing(row, "patientId", resultSet.getString("patient_id"));
        if (!row.has("version") || row.path("version").asText("").isBlank()) row.put("version", resultSet.getInt("version"));
        putIfMissing(row, "status", resultSet.getString("status"));
        putIfMissing(row, "content", resultSet.getString("content"));
        putIfMissing(row, "contentHash", resultSet.getString("content_hash"));
        putIfMissing(row, "model", resultSet.getString("model"));
        putIfMissing(row, "operator", resultSet.getString("operator"));
        putIfMissing(row, "operatorRole", resultSet.getString("operator_role"));
        putIfMissing(row, "generatedAt", resultSet.getString("generated_at"));
        putIfMissing(row, "finalizedAt", resultSet.getString("finalized_at"));
        putIfMissing(row, "voidedAt", resultSet.getString("voided_at"));
        putIfMissing(row, "voidReason", resultSet.getString("void_reason"));
        if (!row.has("fileName")) row.put("fileName", "医生目标病历-V" + row.path("version").asInt(1) + ".docx");
        if (!row.has("downloadUrl")) row.put("downloadUrl", "/clinic-api/medical-record/download?id=" + text(row, "id"));
        if (!row.has("templateVersion")) {
            row.put("templateVersion", GENERATOR_NAME.equals(text(row, "model")) ? TEMPLATE_VERSION : "legacy-text");
        }
        return row;
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

    private String text(JsonNode node, String key, String fallback) {
        String value = text(node, key);
        return value.isBlank() ? fallback : value;
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

    private String firstNonBlank(JsonNode node, String first, String second, String third, String fallback) {
        String firstValue = node.path(first).asText("");
        if (!firstValue.isBlank()) return firstValue;
        String secondValue = node.path(second).asText("");
        if (!secondValue.isBlank()) return secondValue;
        String thirdValue = node.path(third).asText("");
        return thirdValue.isBlank() ? fallback : thirdValue;
    }

    private String normalizeAge(String age) {
        String value = safe(age);
        if (value.isBlank()) return "待补充";
        return value.endsWith("岁") ? value : value + "岁";
    }

    private String inferAge(String patientName, JsonNode record) {
        return firstNonBlank(record, "patientAge", "age", "待补充");
    }

    private boolean isIncomplete(String value) {
        String text = safe(value);
        return text.isBlank()
            || "待补充".equals(text)
            || "未见记录".equals(text)
            || text.contains("____")
            || text.contains("________");
    }

    private String sanitizeFileName(String value) {
        String sanitized = safe(value).replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        return sanitized.isBlank() ? "medical-record" : sanitized;
    }

    private String xmlEscape(String value) {
        return safe(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private static String safe(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }

    public record GenerateRequest(String patientId, String mode) {}
    public record WorkspaceSaveRequest(String patientId, Map<String, String> values) {}
    public record FinalizeRequest(String id) {}
    public record VoidRequest(String id, String reason) {}
    public record DownloadFile(FileSystemResource resource, String fileName) {}
    private record TargetField(
        String section,
        String key,
        String label,
        String kind,
        boolean required,
        boolean aiPolishable,
        String defaultValue,
        String placeholder,
        List<String> sources,
        List<String> anchors
    ) {}

    private static TargetField tf(
        String section,
        String key,
        String label,
        String kind,
        boolean required,
        boolean aiPolishable,
        String defaultValue,
        String placeholder,
        List<String> sources,
        List<String> anchors
    ) {
        return new TargetField(section, key, label, kind, required, aiPolishable, defaultValue, placeholder, sources, anchors);
    }

    private static List<TargetField> buildTargetFields() {
        return List.of(
            tf("病历首页", "patientName", "姓名", "input", true, false, "", "患者真实姓名", List.of("patient.name"), List.of("周xx")),
            tf("病历首页", "gender", "性别", "select", true, false, "", "男/女", List.of(), List.of()),
            tf("病历首页", "age", "年龄", "input", true, false, "", "例如：33", List.of("patientAge"), List.of("33岁")),
            tf("病历首页", "nativePlace", "籍贯", "input", true, false, "", "例如：河南固始", List.of(), List.of("河南固始")),
            tf("病历首页", "occupation", "职业", "input", true, false, "", "例如：农民", List.of(), List.of("农民")),
            tf("病历首页", "maritalStatus", "婚姻", "input", true, false, "", "例如：已婚", List.of(), List.of("已婚")),
            tf("病历首页", "nation", "民族", "input", true, false, "汉族", "例如：汉族", List.of(), List.of()),
            tf("病历首页", "admissionDate", "入院日期", "date", true, false, "", "YYYY-MM-DD", List.of("patient.visitDate"), List.of("2026-02-24")),
            tf("病历首页", "address", "家庭住址", "textarea", true, false, "", "家庭住址/现住址", List.of(), List.of("三角村")),
            tf("病历首页", "historyCollectedAt", "病史采集日期", "date", true, false, "", "YYYY-MM-DD", List.of("admissionDate", "patient.visitDate"), List.of()),
            tf("病历首页", "contactName", "联系人", "input", true, false, "", "联系人姓名", List.of("familyDecisionMaker"), List.of("杨xx")),
            tf("病历首页", "historyProvider", "病史陈述者", "input", true, false, "患者本人", "例如：患者本人", List.of(), List.of("患者本人")),
            tf("病历首页", "contactRelation", "与患者关系", "input", true, false, "", "例如：配偶", List.of("familyDecisionMaker"), List.of("配偶")),
            tf("病历首页", "contactPhone", "联系人电话", "input", true, false, "", "11位手机号", List.of("phone", "familyContact"), List.of("1xxxxxxxxx5")),
            tf("病历首页", "contactAddress", "联系人地址", "textarea", false, false, "同上", "联系人地址", List.of("address"), List.of("同上")),
            tf("病历首页", "historyReliable", "陈述内容是否可靠", "select", true, false, "是", "是/基本可靠/需补充", List.of(), List.of("是")),
            tf("病历首页", "solarTermOnset", "发病节气", "input", false, false, "", "例如：雨水", List.of(), List.of("雨水")),
            tf("病历首页", "allergyHistory", "过敏药物", "textarea", true, true, "", "过敏药物或否认过敏史", List.of(), List.of("无")),
            tf("主诉与现病史", "chiefComplaintText", "主诉", "textarea", true, true, "", "完整主诉", List.of(), List.of("间断便血伴便时肿物脱出1年，加重1月余")),
            tf("主诉与现病史", "presentIllnessText", "现病史", "textarea", true, true, "", "完整现病史段落", List.of("onset"), List.of("患者自诉3年前曾因便血、脱出于门珍行硬化剂注射治疗，术后恢复良好。1年前至今间断便血，滴下状，色鲜红，无痛，便后即止，数天后自行好转，饮酒或食用辛辣食物后症状明显，反复发作，便不尽感明显，时常久蹲排便，进行性加重，便时肛门肿物脱出休息后可自行回纳；近1月症状加重，肿物脱出后需手托回纳。今来院就诊，门诊检查后以“混合痔、直肠黏膜松弛”收入院。患者近期精神、饮食、小便可，入睡难，大便频数，2-3次/日，体重未见明显变化。")),
            tf("主诉与现病史", "admissionReason", "门诊收入院原因", "textarea", true, true, "", "门诊诊断及收入院原因", List.of(), List.of("门诊检查后以“混合痔、直肠黏膜松弛”收入院")),
            tf("主诉与现病史", "generalConditionText", "精神饮食睡眠大小便体重", "textarea", true, true, "", "近期一般情况", List.of("generalCondition"), List.of("患者近期精神、饮食、小便可，入睡难，大便频数，2-3次/日，体重未见明显变化")),
            tf("既往个人婚育家族史", "pastHistory", "既往史", "textarea", true, true, "", "既往史完整段落", List.of("operationHistory", "chronicDisease"), List.of("既往史：否认外伤及慢性病史，否认输血史，预防接种随社会进行，否认过敏史。")),
            tf("既往个人婚育家族史", "personalHistory", "个人史", "textarea", true, true, "", "个人生活史", List.of(), List.of("生长于原籍，否认长期外地居住史，否认特殊化学品及放射性接触史。否认饮酒、吸烟及其他不良生活习惯。否认冶游史。")),
            tf("既往个人婚育家族史", "marriageBirthHistory", "婚育史", "textarea", true, true, "", "婚育情况", List.of(), List.of("适龄结婚，配偶及子女均体健")),
            tf("既往个人婚育家族史", "familyHistory", "家族史", "textarea", true, true, "", "家族史", List.of(), List.of("家族中无传染病、代谢性、糖尿病、血友病、遗传性、肿瘤及类似病史。")),
            tf("中医四诊", "tcmFourDiagnosisText", "中医四诊观察", "textarea", true, true, "", "神志、精神、面色、形体、语声、气味等", List.of("tcmLook"), List.of("神志清，精神可，面色正常，形体微胖，体态自如，言语清晰，语声适中，未闻及异常气息及气味，口干、舌淡紫、无苔，舌中裂纹，脉弦数。")),
            tf("中医四诊", "tongue", "舌象", "input", true, false, "", "舌质、舌苔", List.of(), List.of("舌淡紫、无苔，舌中裂纹")),
            tf("中医四诊", "pulseCondition", "脉象", "input", true, false, "", "例如：脉弦数", List.of(), List.of("脉弦数")),
            tf("体格检查", "vitalSigns", "生命体征", "input", true, false, "", "T/P/R/BP", List.of(), List.of("36.3", "70", "17", "115", "75")),
            tf("体格检查", "generalExam", "一般状况检查", "textarea", true, true, "", "一般状况", List.of(), List.of("发育正常，体型微胖，步入病房，自主体位，言语流利，神志清楚，查体合作，步态正常。")),
            tf("体格检查", "skinMucosaExam", "皮肤和黏膜检查", "textarea", true, true, "", "皮肤黏膜", List.of(), List.of("全身皮肤及粘膜无发绀、黄染及苍白，无皮疹。未见皮下出血。毛发状况：正常。皮肤湿度正常，弹性正常，无水肿，无肝掌，未见蜘蛛痣。")),
            tf("体格检查", "lymphNodeExam", "浅表淋巴结检查", "textarea", true, true, "", "浅表淋巴结", List.of(), List.of("颈前、腋下及腹股沟淋巴结未触及肿大")),
            tf("体格检查", "headOrganExam", "头部及器官检查", "textarea", true, true, "", "头部及五官", List.of(), List.of("头颅正常无畸形，巩膜无黄染，双侧瞳孔等大等圆，直径约3mm，对光反射及调节均正常。双耳廓无畸形，双侧乳突区无压痛，鼻无畸形，鼻中隔居中，嗅觉正常，鼻窦无压痛，无鼻塞，无异常分泌物。口唇红润，无紫绀，口腔黏膜无溃疡及出血点，伸舌居中，齿龈无红肿溢脓，无义齿，双侧扁桃体无肿大，悬雍垂居中，咽部无充血，声音正常。")),
            tf("体格检查", "neckExam", "颈部检查", "textarea", true, true, "", "颈部", List.of(), List.of("颈软无抵抗，气管居中，颈静脉无怒张，肝颈静脉回流征阴性，颈动脉波动正常，甲状腺未触及肿大。")),
            tf("体格检查", "chestExam", "胸部检查", "textarea", true, true, "", "胸肺心", List.of(), List.of("胸廓对称无畸形，呼吸节律正常，胸痹无压痛，无胸骨叩击痛。双肺呼吸音清，未闻及干湿性啰音及胸膜摩擦音。心率70次/分，律齐，各瓣膜听诊区未闻及病理性杂音。")),
            tf("体格检查", "abdomenExam", "腹部检查", "textarea", true, true, "", "腹部", List.of(), List.of("腹部平坦，未见胃肠型及蠕动波，未见腹壁静脉曲张。腹软，无压痛、反跳痛及肌紧张，未触及腹部肿块。肝、脾肋下未触及。双肾区无叩击痛，肠鸣音正常。肠鸣音正常，无气过水声。")),
            tf("体格检查", "externalGenitaliaExam", "外生殖器检查", "textarea", false, true, "未查", "外生殖器检查", List.of(), List.of("未查")),
            tf("体格检查", "spineLimbsExam", "脊柱四肢检查", "textarea", true, true, "", "脊柱四肢", List.of(), List.of("脊柱正常生理弯曲，活动自如，无压痛及叩击痛。四肢活动自如，无畸形，关节无红肿，活动自如，皮温正常。无杵状指、趾，双下肢无明显水肿。")),
            tf("体格检查", "nervousSystemExam", "神经系统检查", "textarea", false, true, "未查", "神经系统检查", List.of(), List.of("未查。")),
            tf("专科及辅助检查", "specialExamFullText", "专科检查", "textarea", true, true, "", "肛门专科检查完整段落", List.of("lithotomyExam", "digitalExam", "anoscope"), List.of("肛门赘皮环状增生，截石位4-5、7、11-1点明显，屏气用腹压可见其缓慢增大，色青紫，质柔软，镜检示直肠黏膜松弛、层叠状，阻塞视野，4、7、11点内痔粘膜隆起糜烂，退镜时4、11点粘膜可脱出肛外。")),
            tf("专科及辅助检查", "auxiliaryExamSummary", "辅助检查结果", "textarea", true, true, "", "检查检验摘要", List.of("bloodRoutine", "ecgResult"), List.of("WBC:7.32X10^9/L    PLT:321X10^9/L    HGB:153g/L    GLU:5.42mmol/L", "胸部DR：心/肺/隔未见明显异常")),
            tf("诊断", "tcmDiagnosis", "中医诊断", "textarea", true, true, "", "中医诊断及证型", List.of(), List.of("痔病（肝胃不和  气阴两虚）")),
            tf("诊断", "westernDiagnosis", "西医诊断", "textarea", true, true, "", "西医诊断多条", List.of(), List.of("混合痔", "直肠粘膜松弛")),
            tf("首次病程记录", "firstCourseAdmissionTime", "首次病程入院时间", "input", true, false, "", "例如：17:45", List.of(), List.of("17:45")),
            tf("首次病程记录", "firstCourseCaseFeatures", "病例特点", "textarea", true, true, "", "病例特点", List.of("presentIllnessText"), List.of("病例特点：")),
            tf("首次病程记录", "tcmSyndromeBasis", "中医辨病辨证依据", "textarea", true, true, "", "中医辨病辨证依据", List.of(), List.of("中医辨病辨证依据：")),
            tf("首次病程记录", "westernDiagnosisBasis", "西医诊断依据", "textarea", true, true, "", "西医诊断依据", List.of(), List.of("西医诊断依据：")),
            tf("首次病程记录", "tcmDifferentialDiagnosis", "中医鉴别诊断", "textarea", true, true, "", "中医鉴别诊断", List.of(), List.of("中医鉴别诊断：")),
            tf("首次病程记录", "westernDifferentialDiagnosis", "西医鉴别诊断", "textarea", true, true, "", "西医鉴别诊断", List.of(), List.of("西医鉴别诊断：")),
            tf("首次病程记录", "diagnosisTreatmentPlan", "诊疗计划", "textarea", true, true, "", "首次病程诊疗计划", List.of("treatmentPlan"), List.of("诊疗计划：")),
            tf("查房记录", "attendingRoundsJson", "主治/副主任医师查房记录", "textarea", true, true, "", "可填写多条查房记录，含日期时间、医师、职称、意见", List.of(), List.of("主治医师查房记录", "副主任医师查房记录")),
            tf("查房记录", "surgeonPreOpRoundText", "手术医师查房记录", "textarea", true, true, "", "术前手术医师查房", List.of(), List.of("手术医师查房记录")),
            tf("术前小结", "preOpSummary", "术前摘要", "textarea", true, true, "", "术前病情摘要", List.of(), List.of("术前小结")),
            tf("术前小结", "preOpDiagnosis", "术前诊断", "textarea", true, true, "", "术前诊断", List.of("westernDiagnosis"), List.of("术前诊断：1、混合痔")),
            tf("术前小结", "operationIndication", "手术指征", "textarea", true, true, "", "手术指征", List.of(), List.of("手术指征：")),
            tf("术前小结", "operationName", "拟施手术方式", "textarea", true, true, "", "拟施手术方式", List.of("mainOperation"), List.of("一次性肛痔套扎吻合器内痔套扎治疗+痔切除术+直肠粘膜悬吊术+肛周药物注射封闭治疗")),
            tf("术前小结", "anesthesiaMethod", "麻醉方式", "input", true, false, "", "麻醉方式", List.of(), List.of("骶管内麻醉")),
            tf("术前小结", "operationNotes", "注意事项", "textarea", true, true, "", "术中注意事项", List.of(), List.of("注意事项：")),
            tf("术前小结", "preOpPreparation", "术前准备", "textarea", true, true, "", "术前准备", List.of(), List.of("术前准备：")),
            tf("术后首次病程", "surgeryDate", "手术时间", "date", true, false, "", "YYYY-MM-DD", List.of("operationDate"), List.of("2026-02-25")),
            tf("术后首次病程", "intraoperativeDiagnosis", "术中诊断", "textarea", true, true, "", "术中诊断", List.of("westernDiagnosis"), List.of("术中诊断：1、混合痔")),
            tf("术后首次病程", "operationBriefProcess", "手术简要经过", "textarea", true, true, "", "手术简要经过", List.of(), List.of("手术简要经过：")),
            tf("术后首次病程", "postOpTreatmentPlan", "术后诊疗计划", "textarea", true, true, "", "术后诊疗计划", List.of(), List.of("术后诊疗计划：")),
            tf("术后首次病程", "postOpFirstRecordAt", "术后首次记录日期时间", "input", true, false, "", "YYYY-MM-DD HH:mm", List.of(), List.of("记录日期时间：2026-02-25 17:45")),
            tf("术后连续查房", "postOpRoundsJson", "术后连续查房记录", "textarea", true, true, "", "可填写多条术后查房/随访病程", List.of(), List.of("术后第一天", "术后第二天", "术后第三天")),
            tf("出院记录", "dischargeAdmissionSituation", "入院情况", "textarea", true, true, "", "出院记录入院情况", List.of("presentIllnessText"), List.of("入院情况：")),
            tf("出院记录", "dischargeTreatmentResult", "诊治经过及结果", "textarea", true, true, "", "含手术日期名称及结果", List.of(), List.of("诊治经过及结果（含手术日期名称及结果）：")),
            tf("出院记录", "dischargeDiagnosis", "出院诊断", "textarea", true, true, "", "出院诊断", List.of("westernDiagnosis", "tcmDiagnosis"), List.of("出院诊断：")),
            tf("出院记录", "dischargeCondition", "出院情况", "textarea", true, true, "", "出院情况及治疗效果", List.of(), List.of("出院情况（含治疗效果）：")),
            tf("出院记录", "dischargeAdvice", "出院医嘱", "textarea", true, true, "", "饮食、排便、生活、用药、护理、复诊", List.of(), List.of("出院医嘱：")),
            tf("出院记录", "tcmCareAdvice", "中医调护", "textarea", true, true, "", "中医调护", List.of(), List.of("中医调护：")),
            tf("签名与日期", "doctorSignature", "医师签名", "input", false, false, "", "医生签名，不自动伪造", List.of("attendingDoctor", "patient.doctor"), List.of("医师签名：")),
            tf("签名与日期", "seniorDoctorSignature", "副主任医师签名", "input", false, false, "", "副主任医师签名", List.of(), List.of("副主任医师签名："))
        );
    }
}
