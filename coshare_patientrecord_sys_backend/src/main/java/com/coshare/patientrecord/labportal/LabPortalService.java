package com.coshare.patientrecord.labportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class LabPortalService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long INGEST_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final int IP_LIMIT_PER_MINUTE = 60;
    private static final int TOKEN_LIMIT_PER_MINUTE = 30;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, Deque<Long>> ipHits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> tokenHits = new ConcurrentHashMap<>();

    @Value("${clinic.lab-portal.shared-secret:}")
    private String sharedSecret;

    public LabPortalService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void ingest(String timestamp, String signature, String body, String remoteIp) {
        verify(timestamp, signature, body);
        JsonNode payload = readTree(body);
        String type = text(payload.path("type"));
        String tokenHash = text(payload.path("tokenHash"));
        if (tokenHash.isBlank()) throw badRequest("tokenHash 缺失");
        if ("REVOKE".equals(type)) {
            jdbcTemplate.update("UPDATE lab_portal_reports SET status = 'REVOKED', updated_at = ? WHERE token_hash = ?",
                LocalDateTime.now(), tokenHash);
            return;
        }
        if (!"UPSERT".equals(type)) throw badRequest("不支持的消息类型");
        JsonNode patient = payload.path("patient");
        JsonNode report = payload.path("report");
        ArrayNode metrics = report.path("metrics").isArray() ? (ArrayNode) report.path("metrics") : objectMapper.createArrayNode();
        String patientName = text(patient.path("name"));
        if (patientName.isBlank()) throw badRequest("患者姓名缺失");
        LocalDateTime expiresAt = parseDateTime(text(payload.path("expiresAt")));
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
            "SELECT id FROM lab_portal_reports WHERE token_hash = ? LIMIT 1", tokenHash
        );
        String id = existing.isEmpty() ? "lpr-" + UUID.randomUUID() : String.valueOf(existing.get(0).get("id"));
        jdbcTemplate.update("""
            INSERT INTO lab_portal_reports (
              id, token_hash, patient_name, gender, age, visit_no, case_token_masked, report_date,
              template_name, metrics_json, critical_count, expires_at, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
            ON DUPLICATE KEY UPDATE
              patient_name = VALUES(patient_name), gender = VALUES(gender), age = VALUES(age),
              visit_no = VALUES(visit_no), case_token_masked = VALUES(case_token_masked),
              report_date = VALUES(report_date), template_name = VALUES(template_name),
              metrics_json = VALUES(metrics_json), critical_count = VALUES(critical_count),
              expires_at = VALUES(expires_at), status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP(3)
            """, id, tokenHash, patientName, text(patient.path("gender")), text(patient.path("age")),
            patient.path("visitNo").asInt(0), text(patient.path("caseTokenMasked")),
            text(report.path("reportDate")), text(report.path("templateName")),
            metrics.toString(), report.path("criticalCount").asInt(0), expiresAt);
    }

    public Map<String, Object> view(String token, String remoteIp, String userAgent) {
        if (token == null || token.isBlank()) throw notFound("报告不存在");
        String tokenHash = sha256(token);
        rateLimit(ipHits, "ip:" + remoteIp, IP_LIMIT_PER_MINUTE);
        rateLimit(tokenHits, "token:" + tokenHash, TOKEN_LIMIT_PER_MINUTE);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, patient_name, gender, age, visit_no, case_token_masked, report_date,
                   template_name, metrics_json, critical_count, expires_at, status
            FROM lab_portal_reports WHERE token_hash = ?
            """, tokenHash);
        if (rows.isEmpty()) throw notFound("报告不存在或链接无效");
        Map<String, Object> row = rows.get(0);
        if (!"ACTIVE".equals(text(row.get("status")))) throw gone("该报告已撤销，请联系医院");
        Object expiresValue = row.get("expires_at");
        LocalDateTime expiresAt = expiresValue instanceof LocalDateTime local
            ? local
            : ((java.sql.Timestamp) expiresValue).toLocalDateTime();
        if (expiresAt.isBefore(LocalDateTime.now())) throw gone("链接已过期，请重新扫码或联系医院");

        jdbcTemplate.update("""
            INSERT INTO lab_portal_view_audits (id, report_id, token_hash, ip, user_agent, viewed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, "lpa-" + UUID.randomUUID(), row.get("id"), tokenHash, remoteIp, truncate(userAgent, 255), LocalDateTime.now());

        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("name", text(row.get("patient_name")));
        patient.put("gender", text(row.get("gender")));
        patient.put("age", text(row.get("age")));
        patient.put("visitNo", row.get("visit_no"));
        patient.put("caseTokenMasked", text(row.get("case_token_masked")));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportDate", text(row.get("report_date")));
        report.put("templateName", text(row.get("template_name")));
        report.put("criticalCount", row.get("critical_count"));
        report.put("metrics", objectMapper.convertValue(
            readTree(text(row.get("metrics_json"))),
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}
        ));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patient", patient);
        result.put("report", report);
        result.put("expiresAt", expiresAt.format(DATE_TIME));
        result.put("viewedAt", LocalDateTime.now().format(DATE_TIME));
        return result;
    }

    @Scheduled(cron = "0 17 * * * *")
    public void purge() {
        try {
            jdbcTemplate.update("DELETE FROM lab_portal_reports WHERE expires_at < ?", LocalDateTime.now().minusDays(7));
            jdbcTemplate.update("DELETE FROM lab_portal_view_audits WHERE viewed_at < ?", LocalDateTime.now().minusDays(90));
        } catch (Exception ignored) {
            // 清理失败不影响查看服务，下个整点重试
        }
    }

    private void verify(String timestamp, String signature, String body) {
        if (sharedSecret == null || sharedSecret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "门户未配置接收密钥");
        if (timestamp == null || signature == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少签名");
        long value;
        try {
            value = Long.parseLong(timestamp);
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "时间戳无效");
        }
        if (Math.abs(System.currentTimeMillis() - value) > INGEST_WINDOW_MILLIS) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请求已过期");
        }
        String expected = hmac(timestamp + "." + body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "签名校验失败");
        }
    }

    private void rateLimit(Map<String, Deque<Long>> hits, String key, int limit) {
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > 60_000L) queue.pollFirst();
            if (queue.size() >= limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "访问过于频繁，请稍后再试");
            queue.addLast(now);
        }
        if (hits.size() > 5000) hits.clear();
    }

    private String hmac(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) builder.append(String.format("%02x", item));
            return builder.toString();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "签名计算失败");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) builder.append(String.format("%02x", item));
            return builder.toString();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "令牌摘要失败");
        }
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value == null || value.isBlank() ? "{}" : value);
        } catch (Exception error) {
            throw badRequest("数据格式不正确");
        }
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value, DATE_TIME);
        } catch (Exception error) {
            throw badRequest("有效期格式不正确");
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException gone(String message) {
        return new ResponseStatusException(HttpStatus.GONE, message);
    }
}
