package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 病种模板库（热更新）：
 * - 预置模板下沉数据库，管理端（医生/管理员/检查室）热编辑即时生效，历史病历按应用时落库文本不受影响；
 * - 「其他/未分型」通用路径的病例按病种名孵化为候选模板（CANDIDATE），同类累计达阈值提醒转正——
 *   个例走通用路径不进库，重复出现的共性才升格为正式模板。
 */
@Service
@Profile("mysql")
public class ClinicDiseaseTemplateService {

    public static final String GENERIC_TEMPLATE_ID = "generic-untyped";
    public static final int PROMOTE_THRESHOLD = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> MANAGE_ROLES = Set.of("doctor", "admin", "inspection");
    private static final String SEED_RESOURCE = "clinic/disease-templates-seed.json";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PreAiEncounterService encounterService;
    private volatile boolean seeded;

    public ClinicDiseaseTemplateService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        PreAiEncounterService encounterService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.encounterService = encounterService;
    }

    // ---------- 读取 ----------

    public List<Map<String, Object>> listActive() {
        ensureSeeded();
        return queryTemplates("WHERE status = 'ACTIVE' ORDER BY updated_at DESC");
    }

    public Map<String, Object> listManage() {
        ensureSeeded();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templates", queryTemplates("ORDER BY status ASC, usage_count DESC, updated_at DESC"));
        result.put("threshold", PROMOTE_THRESHOLD);
        return result;
    }

    private List<Map<String, Object>> queryTemplates(String suffix) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT id, disease, label, version, status, payload_json, usage_count, ready_to_promote, updated_by, updated_at "
                + "FROM clinic_disease_templates " + suffix,
            (resultSet, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", resultSet.getString("id"));
                row.put("disease", resultSet.getString("disease"));
                row.put("label", resultSet.getString("label"));
                row.put("version", resultSet.getString("version"));
                row.put("status", resultSet.getString("status"));
                row.put("payload", json(resultSet.getString("payload_json")));
                row.put("usageCount", resultSet.getInt("usage_count"));
                row.put("readyToPromote", resultSet.getBoolean("ready_to_promote"));
                row.put("updatedBy", resultSet.getString("updated_by"));
                row.put("updatedAt", resultSet.getString("updated_at"));
                return row;
            });
        // payload 序列化后的 maps 里嵌 JsonNode 会影响前端消费，统一转为 Map
        for (Map<String, Object> row : rows) {
            row.put("payload", objectMapper.convertValue(row.get("payload"), Map.class));
        }
        return rows;
    }

    // ---------- 写操作（医生/管理员/检查室） ----------

    public Map<String, Object> create(JsonNode body, SessionUser user) {
        requireManageRole(user);
        String disease = text(body, "disease");
        if (disease.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "病种名称不能为空");
        String id = body.path("id").asText("").isBlank() ? "tpl-" + java.util.UUID.randomUUID().toString().substring(0, 8)
            : text(body, "id");
        String status = "CANDIDATE".equalsIgnoreCase(text(body, "status")) ? "CANDIDATE" : "ACTIVE";
        JsonNode payload = payloadFromRequest(body);
        ObjectNode filled = fillPayload(payload, id, disease, text(body, "label").isBlank() ? disease : text(body, "label"));
        String now = TIME.format(LocalDateTime.now());
        jdbcTemplate.update(
            "INSERT INTO clinic_disease_templates (id, disease, label, version, status, payload_json, usage_count, created_by, created_at, updated_by, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), 0, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE payload_json = VALUES(payload_json), label = VALUES(label), updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)",
            id, disease, filled.path("label").asText(), "anorectal-v2.0", status, toJson(filled),
            user.name(), now, user.name(), now);
        audit(user, "disease-template.create", "新建病种模板：" + disease);
        return get(id);
    }

    public Map<String, Object> update(String id, JsonNode body, SessionUser user) {
        requireManageRole(user);
        Map<String, Object> existing = get(id);
        String disease = text(body, "disease").isBlank() ? String.valueOf(existing.get("disease")) : text(body, "disease");
        JsonNode payload = body.path("payload").isObject() ? body.path("payload")
            : objectMapper.valueToTree(existing.get("payload"));
        if (!body.path("payload").isObject() && hasTemplateFields(body)) {
            payload = payloadFromRequest(body);
        }
        ObjectNode filled = fillPayload(payload, id, disease, text(body, "label").isBlank() ? disease : text(body, "label"));
        String version = bumpVersion(String.valueOf(existing.get("version")));
        String now = TIME.format(LocalDateTime.now());
        jdbcTemplate.update(
            "UPDATE clinic_disease_templates SET disease = ?, label = ?, version = ?, payload_json = CAST(? AS JSON), "
                + "updated_by = ?, updated_at = ? WHERE id = ?",
            disease, filled.path("label").asText(), version, toJson(filled), user.name(), now, id);
        audit(user, "disease-template.update", "编辑病种模板：" + disease + "（" + version + "）");
        return get(id);
    }

    public Map<String, Object> changeStatus(String id, String action, SessionUser user) {
        requireManageRole(user);
        Map<String, Object> existing = get(id);
        String target = switch (action) {
            case "archive" -> "ARCHIVED";
            case "activate" -> "ACTIVE";
            case "promote" -> "ACTIVE";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知操作：" + action);
        };
        jdbcTemplate.update("UPDATE clinic_disease_templates SET status = ?, updated_by = ?, updated_at = ? WHERE id = ?",
            target, user.name(), TIME.format(LocalDateTime.now()), id);
        audit(user, "disease-template." + action, "病种模板 " + existing.get("disease") + " → " + target);
        return get(id);
    }

    // ---------- 孵化钩子（由 PreAiEncounterService 在通用路径保存时调用） ----------

    /**
     * 通用路径（其他/未分型）就诊登记：按规范化病种名孵化候选模板。
     * 同一次就诊只计一次；候选达到阈值时标记 readyToPromote，由管理页提醒转正。
     */
    public void registerGenericUsage(String encounterId, JsonNode stageData, String stageCode, SessionUser user) {
        try {
            String patientCaseId = encounterId;
            List<String> caseIds = jdbcTemplate.queryForList(
                "SELECT patient_case_id FROM pre_ai_encounters WHERE id = ?", String.class, encounterId);
            if (!caseIds.isEmpty() && caseIds.get(0) != null) patientCaseId = caseIds.get(0);
            List<String> diseases = stringList(stageData, "clinicalTemplateDiseases");
            List<String> templateIds = stringList(stageData, "clinicalTemplateIds");
            if (!templateIds.contains(GENERIC_TEMPLATE_ID) || diseases.isEmpty()) return;
            ensureSeeded();
            for (String disease : diseases) {
                String normalized = disease.trim();
                if (normalized.isBlank()) continue;
                if (isPresetDisease(normalized)) continue;
                String candidateId = "tpl-candidate-" + normalized.hashCode();
                Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM clinic_disease_templates WHERE id = ?", Integer.class, candidateId);
                if (exists != null && exists > 0) {
                    Integer counted = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM clinic_disease_templates WHERE id = ? AND (last_encounter_id IS NULL OR last_encounter_id <> ?)",
                        Integer.class, candidateId, encounterId);
                    if (counted == null || counted == 0) continue;
                    jdbcTemplate.update(
                        "UPDATE clinic_disease_templates SET usage_count = usage_count + 1, last_encounter_id = ?, "
                            + "ready_to_promote = (usage_count + 1) >= ?, updated_by = ?, updated_at = ? WHERE id = ?",
                        encounterId, PROMOTE_THRESHOLD, user.name(), TIME.format(LocalDateTime.now()), candidateId);
                } else {
                    ObjectNode payload = objectMapper.createObjectNode();
                    payload.put("id", candidateId);
                    payload.put("disease", normalized);
                    payload.put("label", normalized);
                    payload.put("chiefComplaint", firstNonBlank(stageData, "registrationChiefComplaint", "chiefComplaintText"));
                    payload.put("presentIllness", firstNonBlank(stageData, "registrationCurrentIllness", "presentIllnessOverride", "presentIllness"));
                    payload.put("inspectionConclusion", firstNonBlank(stageData, "inspectionNarrative", "physicalExam"));
                    ArrayNode symptoms = payload.putArray("symptoms");
                    stringList(stageData, "registrationSymptoms").forEach(symptoms::add);
                    stringList(stageData, "chiefComplaint").forEach(symptoms::add);
                    payload.putArray("visual");
                    payload.putArray("digital");
                    payload.putArray("anoscopy");
                    payload.set("slots", objectMapper.createArrayNode());
                    jdbcTemplate.update(
                        "INSERT INTO clinic_disease_templates (id, disease, label, version, status, payload_json, usage_count, last_encounter_id, "
                            + "ready_to_promote, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, 'anorectal-v2.0', 'CANDIDATE', CAST(? AS JSON), 1, ?, 0, ?, ?, ?, ?)",
                        candidateId, normalized, normalized, toJson(payload), encounterId,
                        user.name(), TIME.format(LocalDateTime.now()), user.name(), TIME.format(LocalDateTime.now()));
                }
                audit(user, "disease-template.incubate", "通用路径病例计入候选模板：" + normalized + "（患者档案 " + patientCaseId + "）");
            }
        } catch (Exception ignored) {
            // 孵化失败不阻断接诊主流程
        }
    }

    // ---------- internals ----------

    /** 启动/首次访问时播种预置模板（表空才播种，不覆盖管理端修改）。 */
    private void ensureSeeded() {
        if (seeded) return;
        synchronized (this) {
            if (seeded) return;
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clinic_disease_templates", Integer.class);
            if (count != null && count > 0) {
                seeded = true;
                return;
            }
            try (InputStream stream = new ClassPathResource(SEED_RESOURCE).getInputStream()) {
                String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                JsonNode list = objectMapper.readTree(raw);
                String now = TIME.format(LocalDateTime.now());
                for (JsonNode item : list) {
                    String id = item.path("id").asText();
                    jdbcTemplate.update(
                        "INSERT INTO clinic_disease_templates (id, disease, label, version, status, payload_json, usage_count, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, 'anorectal-v2.0', 'ACTIVE', CAST(? AS JSON), 0, 'system', ?, 'system', ?)",
                        id, item.path("disease").asText(), item.path("label").asText(id), toJson(item), now, now);
                }
            } catch (IOException ignored) {
                // 播种失败时前端回退内置目录
            }
            seeded = true;
        }
    }

    private ObjectNode fillPayload(JsonNode payload, String id, String disease, String label) {
        ObjectNode filled = payload.deepCopy();
        filled.put("id", id);
        if (filled.path("disease").asText("").isBlank()) filled.put("disease", disease);
        if (filled.path("label").asText("").isBlank()) filled.put("label", label);
        if (!filled.has("slots") || !filled.get("slots").isArray()) filled.set("slots", objectMapper.createArrayNode());
        if (!filled.has("symptoms") || !filled.get("symptoms").isArray()) filled.set("symptoms", objectMapper.createArrayNode());
        if (!filled.has("visual") || !filled.get("visual").isArray()) filled.set("visual", objectMapper.createArrayNode());
        if (!filled.has("digital") || !filled.get("digital").isArray()) filled.set("digital", objectMapper.createArrayNode());
        if (!filled.has("anoscopy") || !filled.get("anoscopy").isArray()) filled.set("anoscopy", objectMapper.createArrayNode());
        return filled;
    }

    private JsonNode payloadFromRequest(JsonNode body) {
        if (body.path("payload").isObject()) return body.path("payload");
        ObjectNode payload = body.isObject() ? ((ObjectNode) body).deepCopy() : objectMapper.createObjectNode();
        payload.remove(List.of("status"));
        return payload;
    }

    private boolean hasTemplateFields(JsonNode body) {
        return body.has("chiefComplaint")
            || body.has("presentIllness")
            || body.has("inspectionConclusion")
            || body.has("symptoms")
            || body.has("visual")
            || body.has("digital")
            || body.has("anoscopy")
            || body.has("slots");
    }

    private Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = queryTemplates("WHERE id = '" + id.replace("'", "''") + "'");
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "病种模板不存在");
        return rows.get(0);
    }

    private boolean isPresetDisease(String disease) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM clinic_disease_templates WHERE disease = ? AND status = 'ACTIVE' AND id NOT LIKE 'tpl-candidate-%'",
            Integer.class, disease);
        return count != null && count > 0;
    }

    private List<String> stringList(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        JsonNode fieldNode = node == null ? null : node.path(field);
        if (fieldNode == null) return values;
        if (fieldNode.isArray()) fieldNode.forEach(item -> values.add(item.asText("").trim()));
        else if (!fieldNode.isMissingNode() && !fieldNode.isNull()) values.add(fieldNode.asText("").trim());
        return values.stream().filter(value -> !value.isBlank()).toList();
    }

    private String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node == null ? "" : node.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    /** 版本号自增：anorectal-v2.0 → anorectal-v2.1（解析失败则重置为 anorectal-v2.1）。 */
    private String bumpVersion(String current) {
        try {
            var matcher = java.util.regex.Pattern.compile("^(.*v)(\\d+)\\.(\\d+)$").matcher(current == null ? "" : current);
            if (matcher.matches()) {
                return matcher.group(1) + matcher.group(2) + "." + (Integer.parseInt(matcher.group(3)) + 1);
            }
        } catch (Exception ignored) {
            // 解析失败走默认
        }
        return "anorectal-v2.1";
    }

    private void requireManageRole(SessionUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!MANAGE_ROLES.contains(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅医生、检查室或管理员可管理病种模板库");
        }
    }

    private void audit(SessionUser user, String action, String detail) {
        try {
            encounterService.auditExternal(action, user, detail);
        } catch (Exception ignored) {
            // 审计失败不阻断主流程
        }
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
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

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }
}
