package com.coshare.patientrecord.healtharchive;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.medicalrecord.dto.DownloadFile;
import com.coshare.patientrecord.medicalrecord.service.ClinicMedicalRecordService;
import com.coshare.patientrecord.medicalrecord.service.MedicalRecordSourceBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class HealthArchiveService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> EDIT_ROLES = Set.of("doctor", "nurse", "admin");
    private static final Set<String> COMPLETE_ROLES = Set.of("doctor", "admin");
    private static final List<String> RECOVERY_NODES = List.of("术后当日", "术后3天", "术后7天", "术后15天", "术后30天");
    private static final List<String> FOLLOW_UP_NODES =
        List.of("术后1天", "术后3天", "术后7天", "术后15天", "术后30天", "出院3月", "出院6月");
    private static final List<String> RECOVERY_COLUMNS =
        List.of("timeNode", "wound", "pain", "bowel", "edema", "medication", "training", "remark");
    private static final List<String> FOLLOW_UP_COLUMNS =
        List.of("timeNode", "method", "recovery", "adherence", "diet", "review", "feedback", "visitor");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ClinicMedicalRecordService medicalRecordService;
    private final MedicalRecordSourceBuilder sourceBuilder;
    private final HealthArchiveDocxRenderer renderer;
    private final Path archiveRoot;

    public HealthArchiveService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ClinicMedicalRecordService medicalRecordService,
        MedicalRecordSourceBuilder sourceBuilder,
        HealthArchiveDocxRenderer renderer,
        @Value("${clinic.health-archive-dir:${clinic.attachment-dir}/../health-archive}") String archiveDir
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.medicalRecordService = medicalRecordService;
        this.sourceBuilder = sourceBuilder;
        this.renderer = renderer;
        this.archiveRoot = Path.of(archiveDir).toAbsolutePath().normalize();
    }

    public Map<String, Object> load(String encounterId, SessionUser user) {
        requireEncounter(encounterId);
        assertCanRead(encounterId, user);
        Map<String, String> auto = autoFields(encounterId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encounterId", encounterId);
        result.put("auto", auto);
        result.put("aiVersions", aiVersions(encounterId, user));
        result.put("documents", documents(encounterId));
        result.put("draft", draft(encounterId, auto, user));
        return result;
    }

    public Map<String, Object> save(String encounterId, JsonNode form, String sourceRecordId, SessionUser user) {
        requireEncounter(encounterId);
        requireRole(user, EDIT_ROLES, "仅医生、护士或管理员可编辑健康管理档案");
        assertCanRead(encounterId, user);
        if (form == null || !form.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少档案表单内容");
        }
        normalizeTableRows(form);
        String resolvedSource = resolveSourceRecordId(encounterId, sourceRecordId);
        String now = TIME.format(LocalDateTime.now());
        String archiveNo = archiveNo(encounterId);
        jdbcTemplate.update("""
            INSERT INTO health_archive_drafts (
              id, encounter_id, source_record_id, status, archive_no, form_json, revision,
              created_by, created_by_role, created_at, updated_by, updated_by_role, updated_at
            ) VALUES (?, ?, ?, 'DRAFT', ?, CAST(? AS JSON), 1, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              source_record_id = VALUES(source_record_id),
              archive_no = VALUES(archive_no),
              form_json = VALUES(form_json),
              revision = health_archive_drafts.revision + 1,
              updated_by = VALUES(updated_by),
              updated_by_role = VALUES(updated_by_role),
              updated_at = VALUES(updated_at)
            """,
            "harch-" + UUID.randomUUID(), encounterId, resolvedSource, archiveNo, toJson(form),
            user.name(), user.role(), now, user.name(), user.role(), now
        );
        writeAudit(encounterId, user, "保存健康管理档案草稿");
        ObjectNode draft = draft(encounterId, Map.of(), user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draft", draft);
        return result;
    }

    public Map<String, Object> complete(String encounterId, JsonNode form, String sourceRecordId, SessionUser user) {
        requireEncounter(encounterId);
        requireRole(user, COMPLETE_ROLES, "仅医生或管理员可完成健康管理档案合并");
        assertCanRead(encounterId, user);
        if (sourceRecordId == null || sourceRecordId.isBlank()) {
            sourceRecordId = latestAiRecordId(encounterId, user);
        }
        if (sourceRecordId == null || sourceRecordId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该就诊暂无 AI 生成的病历版本，无法合并");
        }
        if (form == null || !form.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少档案表单内容");
        }
        normalizeTableRows(form);
        save(encounterId, form, sourceRecordId, user);

        byte[] aiDocx;
        String aiFileName;
        try {
            DownloadFile download = medicalRecordService.download(sourceRecordId, user);
            aiDocx = download.resource().getContentAsByteArray();
            aiFileName = download.fileName();
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取 AI 病历文档失败", error);
        }

        Map<String, String> auto = autoFields(encounterId);
        // 表单中医生/护士同步或修正过的基本信息优先于系统带出值
        JsonNode basicForm = form.path("basic");
        if (basicForm.isObject()) {
            basicForm.fields().forEachRemaining(field -> auto.put(field.getKey(), field.getValue().asText("")));
        }
        JsonNode draftRow = draftRow(encounterId);
        String archiveNo = draftRow.path("archive_no").asText("");
        String archiveDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        byte[] merged;
        try {
            merged = renderer.render(aiDocx, archiveNo, archiveDate,
                objectMapper.valueToTree(auto), form);
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage(), error);
        }

        int version = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM health_archive_documents WHERE encounter_id = ?",
            Integer.class, encounterId);
        String documentId = "harchdoc-" + UUID.randomUUID();
        String fileName = "健康管理档案-" + auto.getOrDefault("name", "患者") + "-V" + version + ".docx";
        Path directory = archiveRoot.resolve(encounterId);
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(documentId + ".docx");
            Files.write(target, merged);
            jdbcTemplate.update("""
                INSERT INTO health_archive_documents (
                  id, encounter_id, draft_id, source_record_id, version, file_name, storage_path,
                  sha256, file_size, status, created_by, created_by_role, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """,
                documentId, encounterId, draftRow.path("id").asText(""), sourceRecordId, version,
                fileName, target.toString(), sha256(merged), merged.length, user.name(), user.role(),
                TIME.format(LocalDateTime.now())
            );
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "合并文档写入失败", error);
        }
        jdbcTemplate.update(
            "UPDATE health_archive_drafts SET status = 'COMPLETED', completed_at = ?, completed_by = ?, completed_by_role = ? "
                + "WHERE encounter_id = ?",
            TIME.format(LocalDateTime.now()), user.name(), user.role(), encounterId
        );
        writeAudit(encounterId, user, "完成健康管理档案合并 V" + version);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", documentId);
        document.put("version", version);
        document.put("fileName", fileName);
        document.put("createdAt", TIME.format(LocalDateTime.now()));
        document.put("downloadUrl", "/clinic-api/health-archive/documents/" + documentId + "/download");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", document);
        result.put("aiRecordFileName", aiFileName);
        return result;
    }

    public DownloadFile downloadDocument(String documentId, SessionUser user) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT encounter_id, file_name, storage_path FROM health_archive_documents WHERE id = ? AND status = 'ACTIVE'",
            (resultSet, rowNum) -> Map.of(
                "encounterId", resultSet.getString("encounter_id"),
                "fileName", resultSet.getString("file_name"),
                "storagePath", resultSet.getString("storage_path")
            ),
            documentId
        );
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "合并文档不存在");
        Map<String, Object> row = rows.get(0);
        assertCanRead(String.valueOf(row.get("encounterId")), user);
        Path path = Path.of(String.valueOf(row.get("storagePath"))).toAbsolutePath().normalize();
        if (!path.startsWith(archiveRoot) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "合并文档文件不存在");
        }
        writeAudit(String.valueOf(row.get("encounterId")), user, "下载健康管理档案合并文档");
        return new DownloadFile(new org.springframework.core.io.FileSystemResource(path), String.valueOf(row.get("fileName")));
    }

    // ---------- internals ----------

    private ObjectNode draft(String encounterId, Map<String, String> auto, SessionUser user) {
        JsonNode row = draftRow(encounterId);
        ObjectNode draft = objectMapper.createObjectNode();
        JsonNode form = row.has("form_json")
            ? json(row.get("form_json").asText("{}"))
            : defaultForm();
        ObjectNode merged = objectMapper.createObjectNode();
        merged.set("auto", objectMapper.valueToTree(auto));
        if (form.isObject()) {
            form.fields().forEachRemaining(field -> merged.set(field.getKey(), field.getValue()));
        }
        // 基本信息同步：已知患者数据带入可编辑的 basic 区（医生/护士可改，完成合并时以表单值优先）
        ObjectNode basic = objectMapper.createObjectNode();
        auto.forEach(basic::put);
        JsonNode savedBasic = form.path("basic");
        if (savedBasic.isObject()) {
            savedBasic.fields().forEachRemaining(field -> basic.put(field.getKey(), field.getValue().asText("")));
        }
        merged.set("basic", basic);
        merged.set("recoveryRows", tableRows(row, form, "recoveryRows", RECOVERY_NODES, RECOVERY_COLUMNS));
        merged.set("followUpRows", tableRows(row, form, "followUpRows", FOLLOW_UP_NODES, FOLLOW_UP_COLUMNS));
        draft.put("encounterId", encounterId);
        draft.put("id", row.path("id").asText(""));
        draft.put("status", row.path("status").asText("DRAFT"));
        draft.put("archiveNo", row.path("archive_no").asText(""));
        draft.put("revision", row.path("revision").asInt(0));
        draft.put("sourceRecordId", row.path("source_record_id").asText(""));
        draft.put("updatedAt", row.path("updated_at").asText(""));
        draft.put("updatedBy", row.path("updated_by").asText(""));
        draft.put("completedAt", row.path("completed_at").asText(""));
        draft.set("form", merged);
        return draft;
    }

    private ArrayNode tableRows(JsonNode row, JsonNode savedForm, String field, List<String> nodes, List<String> columns) {
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode savedRows = savedForm.path(field);
        for (int i = 0; i < nodes.size(); i++) {
            ObjectNode item = objectMapper.createObjectNode();
            JsonNode savedRow = savedRows.isArray() ? savedRows.path(i) : objectMapper.createObjectNode();
            for (int c = 0; c < columns.size(); c++) {
                String key = columns.get(c);
                item.put(key, c == 0 ? nodes.get(i) : savedRow.path(key).asText(""));
            }
            result.add(item);
        }
        return result;
    }

    private JsonNode draftRow(String encounterId) {
        List<JsonNode> rows = jdbcTemplate.query(
            "SELECT id, status, archive_no, form_json, revision, source_record_id, updated_at, updated_by, completed_at "
                + "FROM health_archive_drafts WHERE encounter_id = ?",
            (resultSet, rowNum) -> {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("id", resultSet.getString("id"));
                node.put("status", resultSet.getString("status"));
                node.put("archive_no", resultSet.getString("archive_no"));
                node.put("form_json", resultSet.getString("form_json"));
                node.put("revision", resultSet.getInt("revision"));
                node.put("source_record_id", resultSet.getString("source_record_id"));
                node.put("updated_at", resultSet.getString("updated_at"));
                node.put("updated_by", resultSet.getString("updated_by"));
                node.put("completed_at", resultSet.getString("completed_at"));
                return node;
            },
            encounterId
        );
        return rows.isEmpty() ? objectMapper.createObjectNode() : rows.get(0);
    }

    private ArrayNode aiVersions(String encounterId, SessionUser user) {
        ArrayNode result = objectMapper.createArrayNode();
        Map<String, Object> versions = medicalRecordService.versions("", encounterId, user, 50);
        JsonNode list = objectMapper.valueToTree(versions).path("versions");
        if (list.isArray()) {
            for (JsonNode item : list) {
                if ("docx-template".equalsIgnoreCase(item.path("model").asText("docx-template"))) continue;
                if ("voided".equals(item.path("status").asText(""))) continue;
                ObjectNode entry = result.addObject();
                entry.put("id", item.path("id").asText(""));
                entry.put("version", item.path("version").asInt(0));
                entry.put("model", item.path("model").asText(""));
                entry.put("status", item.path("status").asText(""));
                entry.put("fileName", item.path("fileName").asText(""));
                entry.put("generatedAt", item.path("generatedAt").asText(""));
                entry.put("operatorRole", item.path("operatorRole").asText(""));
            }
        }
        return result;
    }

    private ArrayNode documents(String encounterId) {
        ArrayNode result = objectMapper.createArrayNode();
        jdbcTemplate.query(
            "SELECT id, version, file_name, status, created_at, created_by_role FROM health_archive_documents "
                + "WHERE encounter_id = ? AND status = 'ACTIVE' ORDER BY version DESC",
            resultSet -> {
                ObjectNode item = result.addObject();
                item.put("id", resultSet.getString("id"));
                item.put("version", resultSet.getInt("version"));
                item.put("fileName", resultSet.getString("file_name"));
                item.put("status", resultSet.getString("status"));
                item.put("createdAt", resultSet.getString("created_at"));
                item.put("createdByRole", resultSet.getString("created_by_role"));
                item.put("downloadUrl", "/clinic-api/health-archive/documents/" + resultSet.getString("id") + "/download");
            },
            encounterId
        );
        return result;
    }

    private Map<String, String> autoFields(String encounterId) {
        Map<String, String> auto = new LinkedHashMap<>();
        List<JsonNode> encounters = jdbcTemplate.query(
            "SELECT patient_json FROM pre_ai_encounters WHERE id = ?",
            (resultSet, rowNum) -> json(resultSet.getString("patient_json")),
            encounterId
        );
        JsonNode patient = encounters.isEmpty() ? objectMapper.createObjectNode() : encounters.get(0);
        auto.put("name", firstNonBlank(patient, "name", "patientName"));
        auto.put("gender", firstNonBlank(patient, "gender", "sex"));
        auto.put("age", firstNonBlank(patient, "age"));
        auto.put("phone", firstNonBlank(patient, "phone", "mobile", "telephone", "contactPhone"));
        auto.put("address", firstNonBlank(patient, "address", "homeAddress"));
        auto.put("insurance", firstNonBlank(patient, "insurance", "insuranceType", "medicalInsurance"));
        String westernDx = "";
        String tcmDx = "";
        List<String> diagnoses = jdbcTemplate.query(
            "SELECT diagnosis_type, diagnosis_text FROM pre_ai_diagnoses WHERE encounter_id = ? ORDER BY sort_no ASC",
            (resultSet, rowNum) -> resultSet.getString("diagnosis_type") + "|" + resultSet.getString("diagnosis_text"),
            encounterId
        );
        for (String line : diagnoses) {
            int split = line.indexOf('|');
            String type = split > 0 ? line.substring(0, split) : "";
            String value = split > 0 ? line.substring(split + 1) : line;
            if (value.isBlank()) continue;
            if (westernDx.isBlank() && type.contains("西")) westernDx = value;
            if (tcmDx.isBlank() && type.contains("中")) tcmDx = value;
        }
        auto.put("westernDx", westernDx);
        auto.put("tcmDx", tcmDx);
        return auto;
    }

    private String resolveSourceRecordId(String encounterId, String sourceRecordId) {
        if (sourceRecordId != null && !sourceRecordId.isBlank()) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clinic_generated_medical_records WHERE id = ?",
                Integer.class, sourceRecordId);
            if (count == null || count != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选 AI 病历版本不存在");
            }
            return sourceRecordId;
        }
        return latestAiRecordId(encounterId, null);
    }

    private String latestAiRecordId(String encounterId, SessionUser user) {
        ArrayNode versions = user == null ? aiVersionsUnauthenticated(encounterId) : aiVersions(encounterId, user);
        return versions.isEmpty() ? "" : versions.get(0).path("id").asText("");
    }

    private ArrayNode aiVersionsUnauthenticated(String encounterId) {
        // complete 流程在权限校验之后调用，此处仅做数据读取（无用户上下文的兜底）
        Map<String, Object> versions = medicalRecordService.versions("", encounterId, systemUser(), 50);
        ArrayNode result = objectMapper.createArrayNode();
        JsonNode list = objectMapper.valueToTree(versions).path("versions");
        if (list.isArray()) {
            for (JsonNode item : list) {
                if ("docx-template".equalsIgnoreCase(item.path("model").asText("docx-template"))) continue;
                result.add(item.deepCopy());
            }
        }
        return result;
    }

    private SessionUser systemUser() {
        return new SessionUser("system", "system", "系统", "admin", "管理员", "", "", false,
            java.time.Instant.now().plusSeconds(60));
    }

    private void normalizeTableRows(JsonNode form) {
        normalizeRows(form, "recoveryRows", RECOVERY_COLUMNS);
        normalizeRows(form, "followUpRows", FOLLOW_UP_COLUMNS);
    }

    private void normalizeRows(JsonNode form, String field, List<String> columns) {
        JsonNode rows = form.path(field);
        if (!rows.isArray()) {
            if (form instanceof ObjectNode object) object.set(field, objectMapper.createArrayNode());
            return;
        }
        for (JsonNode row : rows) {
            if (row instanceof ObjectNode object) {
                ArrayNode normalized = objectMapper.createArrayNode();
                for (String column : columns) normalized.add(object.path(column).asText(""));
                object.set(field, normalized);
                ArrayNode cells = (ArrayNode) object.get(field);
                ObjectNode rebuilt = objectMapper.createObjectNode();
                for (int c = 0; c < columns.size() && c < cells.size(); c++) {
                    rebuilt.put(columns.get(c), cells.get(c).asText(""));
                }
                object.set(field, rebuilt);
            }
        }
    }

    private String archiveNo(String encounterId) {
        JsonNode row = draftRow(encounterId);
        String existing = row.path("archive_no").asText("");
        if (!existing.isBlank()) return existing;
        String suffix = encounterId.length() > 6 ? encounterId.substring(encounterId.length() - 6) : encounterId;
        return "HA-" + suffix.toUpperCase();
    }

    private void assertCanRead(String encounterId, SessionUser user) {
        sourceBuilder.assertCanReadScope("preai:" + encounterId, user);
    }

    private void requireRole(SessionUser user, Set<String> roles, String message) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!roles.contains(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private void requireEncounter(String encounterId) {
        if (encounterId == null || encounterId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少前置病例ID");
        }
    }

    private void writeAudit(String encounterId, SessionUser user, String action) {
        try {
            ObjectNode raw = objectMapper.createObjectNode();
            raw.put("encounterId", encounterId);
            jdbcTemplate.update(
                "INSERT INTO clinic_audit_logs (id, time, operator, role, patient, patient_id, module, action, result, raw_json) "
                    + "VALUES (?, ?, ?, ?, '', ?, ?, ?, '成功', CAST(? AS JSON))",
                "audit-" + UUID.randomUUID(), TIME.format(LocalDateTime.now()), user.name(), user.role(),
                encounterId, "健康管理档案", action, toJson(raw)
            );
        } catch (Exception ignored) {
            // 审计失败不阻断主流程
        }
    }

    private String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception error) {
            return "";
        }
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception error) {
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return "{}";
        }
    }

    private String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String defaultFormJson() {
        return "{}";
    }

    private JsonNode defaultForm() {
        return json(defaultFormJson());
    }
}
