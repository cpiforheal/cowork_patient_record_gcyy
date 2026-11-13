package com.coshare.patientrecord.preai;

import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.service.RoleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class LabReportPublishService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> PUBLISH_ROLES = Set.of("admin", "doctor");
    private static final int EXPIRE_HOURS = 24;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public LabReportPublishService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${clinic.lab-portal.enabled:false}")
    private boolean enabled;

    @Value("${clinic.lab-portal.base-url:}")
    private String baseUrl;

    @Transactional
    public Map<String, Object> publish(String encounterId, String reportId, SessionUser user) {
        requirePublisher(user);
        if (!enabled || baseUrl == null || baseUrl.isBlank()) {
            throw conflict("患者端发布通道未启用，请先配置 clinic.lab-portal 参数");
        }
        List<Map<String, Object>> reports = jdbcTemplate.queryForList("""
            SELECT id, template_name, report_date, metrics_json, version, status
            FROM pre_ai_lab_reports WHERE id = ? AND encounter_id = ?
            """, reportId, encounterId);
        if (reports.isEmpty()) throw notFound("化验报告不存在");
        Map<String, Object> report = reports.get(0);
        if (!"ACTIVE".equals(text(report.get("status")))) throw conflict("仅可发布当前有效版本的化验报告");

        List<Map<String, Object>> encounters = jdbcTemplate.queryForList("""
            SELECT id, case_token, visit_no, status, patient_json
            FROM pre_ai_encounters WHERE id = ?
            """, encounterId);
        if (encounters.isEmpty()) throw notFound("就诊记录不存在");
        Map<String, Object> encounter = encounters.get(0);
        String encounterStatus = text(encounter.get("status"));
        if (Set.of("CANCELLED", "WITHDRAWN").contains(encounterStatus)) throw conflict("已取消或撤回的就诊不可发布");

        Integer completedLab = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM pre_ai_auxiliary_tasks
            WHERE encounter_id = ? AND task_type = 'LAB' AND status = 'COMPLETED'
            """, Integer.class, encounterId);
        if (completedLab == null || completedLab == 0) throw conflict("化验报告尚未完成交接，不能发布给患者");

        JsonNode patient = readJson(text(encounter.get("patient_json")));
        String patientName = text(patient.path("patientName"));
        if (patientName.isBlank()) throw conflict("患者姓名缺失，不能发布");

        ArrayNode metrics = objectMapper.createArrayNode();
        int criticalCount = 0;
        for (JsonNode metric : readJson(text(report.get("metrics_json")))) {
            String value = text(metric.path("value"));
            if (value.isBlank()) continue;
            boolean critical = metric.path("critical").asBoolean(false) || "CRITICAL".equals(text(metric.path("severity")));
            if (critical) criticalCount++;
            ObjectNode item = metrics.addObject();
            item.put("name", text(metric.path("name")));
            item.put("shortName", text(metric.path("shortName")));
            item.put("value", value);
            item.put("unit", text(metric.path("unit")));
            item.put("reference", text(metric.path("reference")));
            item.put("severity", critical ? "CRITICAL" : text(metric.path("severity")));
            item.put("critical", critical);
        }
        if (metrics.isEmpty()) throw conflict("报告没有可发布的指标结果");

        String token = newToken();
        String tokenHash = sha256(token);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(EXPIRE_HOURS);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "UPSERT");
        payload.put("tokenHash", tokenHash);
        payload.put("expiresAt", expiresAt.format(DATE_TIME));
        ObjectNode patientNode = payload.putObject("patient");
        patientNode.put("name", patientName);
        patientNode.put("gender", text(patient.path("gender")));
        patientNode.put("age", text(patient.path("age")));
        patientNode.put("visitNo", encounter.get("visit_no") == null ? 0 : ((Number) encounter.get("visit_no")).intValue());
        patientNode.put("caseTokenMasked", maskToken(text(encounter.get("case_token"))));
        ObjectNode reportNode = payload.putObject("report");
        reportNode.put("reportDate", text(report.get("report_date")));
        reportNode.put("templateName", text(report.get("template_name")));
        reportNode.set("metrics", metrics);
        reportNode.put("criticalCount", criticalCount);

        jdbcTemplate.update("""
            UPDATE clinic_lab_portal_publications
            SET revoked_at = ?, revoked_by = ?, push_status = 'PENDING', attempts = 0,
                next_attempt_at = ?, last_error = NULL
            WHERE report_id = ? AND revoked_at IS NULL
            """, now, user.name(), now, reportId);

        String publicationId = "lpp-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO clinic_lab_portal_publications (
              id, report_id, encounter_id, report_version, token_hash, payload_json,
              expires_at, push_status, attempts, next_attempt_at, published_by, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
            """, publicationId, reportId, encounterId,
            report.get("version") == null ? 1 : ((Number) report.get("version")).intValue(),
            tokenHash, toJson(payload), expiresAt, now, user.name(), now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicationId", publicationId);
        result.put("token", token);
        result.put("url", baseUrl.replaceAll("/+$", "") + "/?token=" + token);
        result.put("expiresAt", expiresAt.format(DATE_TIME));
        result.put("pushStatus", "PENDING");
        return result;
    }

    @Transactional
    public Map<String, Object> revoke(String publicationId, SessionUser user) {
        requirePublisher(user);
        int updated = jdbcTemplate.update("""
            UPDATE clinic_lab_portal_publications
            SET revoked_at = ?, revoked_by = ?, push_status = 'PENDING', attempts = 0,
                next_attempt_at = ?, last_error = NULL
            WHERE id = ? AND revoked_at IS NULL
            """, LocalDateTime.now(), user.name(), LocalDateTime.now(), publicationId);
        if (updated == 0) throw conflict("该发布记录已撤销或不存在");
        return Map.of("publicationId", publicationId, "revoked", true);
    }

    public Map<String, Object> publications(String encounterId, SessionUser user) {
        requirePublisher(user);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, report_id, report_version, expires_at, revoked_at, push_status, attempts,
                   last_error, published_by, published_at
            FROM clinic_lab_portal_publications
            WHERE encounter_id = ?
            ORDER BY published_at DESC
            LIMIT 20
            """, encounterId);
        return Map.of("list", rows);
    }

    private void requirePublisher(SessionUser user) {
        if (user == null || !PUBLISH_ROLES.contains(RoleCatalog.canonicalize(user.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员或医生可发布检验报告");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) builder.append(String.format("%02x", item));
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("令牌摘要生成失败", error);
        }
    }

    private String maskToken(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() <= 4) return "****";
        return "****" + value.substring(value.length() - 4);
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("发布数据序列化失败", error);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return objectMapper.createArrayNode();
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            return objectMapper.createArrayNode();
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
