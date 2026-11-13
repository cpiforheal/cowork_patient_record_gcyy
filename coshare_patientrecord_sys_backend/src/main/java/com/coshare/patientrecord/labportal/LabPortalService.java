package com.coshare.patientrecord.labportal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private static final long SESSION_MINUTES = 30;
    private static final int LOCK_THRESHOLD = 5;
    private static final int LOCK_MINUTES = 20;
    private static final int IP_LOOKUP_LIMIT_PER_MINUTE = 30;
    private static final int VISIBLE_MONTHS = 6;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, PortalSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> ipHits = new ConcurrentHashMap<>();

    @Value("${clinic.lab-portal.shared-secret:}")
    private String sharedSecret;

    public LabPortalService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void ingest(String timestamp, String signature, String body) {
        verify(timestamp, signature, body);
        JsonNode payload = readTree(body);
        String type = text(payload.path("type"));
        String identityKey = text(payload.path("identityKey"));
        String encounterId = text(payload.path("encounter").path("encounterId"));
        if ("REMOVE".equals(type)) {
            String removeKey = identityKey.isBlank() ? text(payload.path("identityKey")) : identityKey;
            encounterId = encounterId.isBlank() ? text(payload.path("encounterId")) : encounterId;
            jdbcTemplate.update(
                "UPDATE lab_portal_patient_reports SET status = 'REMOVED', updated_at = ? WHERE identity_key = ? AND encounter_id = ?",
                LocalDateTime.now(), removeKey, encounterId
            );
            return;
        }
        if (!"SNAPSHOT".equals(type)) throw badRequest("不支持的消息类型");
        if (identityKey.isBlank()) throw badRequest("identityKey 缺失");
        if (encounterId.isBlank()) throw badRequest("encounterId 缺失");
        JsonNode patient = payload.path("patient");
        int visitNo = patient.path("visitNo").asInt(1);
        String visitDate = text(payload.path("encounter").path("visitDate"));
        LocalDate visit = parseDate(visitDate);
        JsonNode reports = payload.path("reports");
        List<String> reportIds = new ArrayList<>();
        if (reports.isArray()) {
            for (JsonNode report : reports) {
                String reportId = text(report.path("reportId"));
                if (reportId.isBlank()) continue;
                reportIds.add(reportId);
                String id = "lppr-" + UUID.randomUUID();
                jdbcTemplate.update("""
                    INSERT INTO lab_portal_patient_reports (
                      id, identity_key, encounter_id, report_id, visit_no, visit_date, report_date,
                      template_name, metrics_json, abnormal_count, critical_count, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    ON DUPLICATE KEY UPDATE
                      encounter_id = VALUES(encounter_id), visit_no = VALUES(visit_no), visit_date = VALUES(visit_date),
                      report_date = VALUES(report_date), template_name = VALUES(template_name),
                      metrics_json = VALUES(metrics_json), abnormal_count = VALUES(abnormal_count),
                      critical_count = VALUES(critical_count), status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP(3)
                    """, id, identityKey, encounterId, reportId, visitNo, visit,
                    text(report.path("reportDate")), text(report.path("templateName")),
                    report.path("metrics").toString(), report.path("abnormalCount").asInt(0),
                    report.path("criticalCount").asInt(0));
            }
        }
        if (reportIds.isEmpty()) {
            jdbcTemplate.update(
                "UPDATE lab_portal_patient_reports SET status = 'REMOVED', updated_at = ? WHERE identity_key = ? AND encounter_id = ?",
                LocalDateTime.now(), identityKey, encounterId
            );
        } else {
            String placeholders = String.join(",", reportIds.stream().map(value -> "?").toList());
            List<Object> args = new ArrayList<>();
            args.add(LocalDateTime.now());
            args.add(identityKey);
            args.add(encounterId);
            args.addAll(reportIds);
            jdbcTemplate.update(
                "UPDATE lab_portal_patient_reports SET status = 'REMOVED', updated_at = ? "
                    + "WHERE identity_key = ? AND encounter_id = ? AND report_id NOT IN (" + placeholders + ")",
                args.toArray()
            );
        }
    }

    public LookupResult lookup(String name, String mode, String lastDigits, String remoteIp, String userAgent) {
        String normalizedName = name == null ? "" : name.replaceAll("\\s+", "").trim();
        String identityMode = "PHONE".equalsIgnoreCase(mode) ? "PHONE" : "ID";
        String digits = lastDigits == null ? "" : lastDigits.replaceAll("\\s+", "").trim();
        if (normalizedName.isBlank() || digits.isBlank()) throw badRequest("请填写姓名和验证信息");
        if (identityMode.equals("ID") && (digits.length() < 4 || digits.length() > 6)) throw badRequest("请输入证件号后六位");
        if (identityMode.equals("PHONE") && digits.length() != 4) throw badRequest("请输入手机号后四位");
        rateLimit(ipHits, "ip:" + remoteIp, IP_LOOKUP_LIMIT_PER_MINUTE);
        String identityKey = identityKey(identityMode, normalizedName, digits);
        String failureKey = remoteIp + "|" + identityKey;
        if (isLocked(failureKey)) {
            audit(identityKey, "LOOKUP", false, remoteIp, userAgent);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请 20 分钟后再试");
        }
        List<Map<String, Object>> rows = loadVisibleReports(identityKey);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patient", Map.of("name", normalizedName));
        if (rows.isEmpty()) {
            recordFailure(failureKey);
            audit(identityKey, "LOOKUP", false, remoteIp, userAgent);
            data.put("groups", List.of());
            data.put("empty", true);
            return new LookupResult(null, data);
        }
        failures.remove(failureKey);
        String token = "lps-" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new PortalSession(identityKey, normalizedName, LocalDateTime.now().plusMinutes(SESSION_MINUTES)));
        audit(identityKey, "LOOKUP", true, remoteIp, userAgent);
        data.putAll(grouped(rows));
        return new LookupResult(token, data);
    }

    public Map<String, Object> reports(String sessionToken) {
        PortalSession session = requireSession(sessionToken);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patient", Map.of("name", session.name()));
        data.putAll(grouped(loadVisibleReports(session.identityKey())));
        return data;
    }

    public Map<String, Object> report(String sessionToken, String reportId, String remoteIp, String userAgent) {
        PortalSession session = requireSession(sessionToken);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT report_id, report_date, template_name, metrics_json, critical_count
            FROM lab_portal_patient_reports
            WHERE identity_key = ? AND report_id = ? AND status = 'ACTIVE' AND visit_date >= ?
            """, session.identityKey(), reportId, LocalDate.now().minusMonths(VISIBLE_MONTHS));
        if (rows.isEmpty()) throw notFound("报告不存在或已过期");
        Map<String, Object> row = rows.get(0);
        audit(session.identityKey(), "VIEW", true, remoteIp, userAgent);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", text(row.get("report_id")));
        result.put("reportDate", text(row.get("report_date")));
        result.put("templateName", text(row.get("template_name")));
        result.put("criticalCount", row.get("critical_count"));
        result.put("metrics", objectMapper.convertValue(
            readTree(text(row.get("metrics_json"))),
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}
        ));
        return result;
    }

    public void logout(String sessionToken) {
        if (sessionToken != null) sessions.remove(sessionToken);
    }

    @Scheduled(cron = "0 23 * * * *")
    public void purge() {
        try {
            LocalDateTime now = LocalDateTime.now();
            sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
            jdbcTemplate.update("DELETE FROM lab_portal_patient_reports WHERE status = 'REMOVED' AND updated_at < ?", now.minusMonths(7));
            jdbcTemplate.update("DELETE FROM lab_portal_access_logs WHERE created_at < ?", now.minusDays(90));
            jdbcTemplate.update("DELETE FROM lab_portal_reports WHERE expires_at < ?", now.minusDays(30));
            jdbcTemplate.update("DELETE FROM lab_portal_view_audits WHERE viewed_at < ?", now.minusDays(90));
        } catch (Exception ignored) {
            // 清理失败不阻断查询，下个整点重试
        }
    }

    private List<Map<String, Object>> loadVisibleReports(String identityKey) {
        return jdbcTemplate.queryForList("""
            SELECT encounter_id, visit_no, DATE_FORMAT(visit_date, '%Y-%m-%d') AS visit_text,
                   report_id, report_date, template_name, abnormal_count, critical_count
            FROM lab_portal_patient_reports
            WHERE identity_key = ? AND status = 'ACTIVE' AND visit_date >= ?
            ORDER BY visit_date DESC, report_date DESC
            """, identityKey, LocalDate.now().minusMonths(VISIBLE_MONTHS));
    }

    private Map<String, Object> grouped(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String visit = text(row.get("visit_text"));
            Map<String, Object> group = groups.computeIfAbsent(visit, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("visitDate", key);
                item.put("reports", new ArrayList<Map<String, Object>>());
                return item;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reports = (List<Map<String, Object>>) group.get("reports");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("reportId", text(row.get("report_id")));
            report.put("reportDate", text(row.get("report_date")));
            report.put("templateName", text(row.get("template_name")));
            report.put("abnormalCount", row.get("abnormal_count"));
            report.put("criticalCount", row.get("critical_count"));
            reports.add(report);
        }
        List<Map<String, Object>> list = new ArrayList<>(groups.values());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", list);
        result.put("empty", list.isEmpty());
        return result;
    }

    private PortalSession requireSession(String token) {
        if (token == null || token.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先验证身份");
        PortalSession session = sessions.get(token);
        if (session == null || session.expiresAt().isBefore(LocalDateTime.now())) {
            sessions.remove(token);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新验证");
        }
        sessions.put(token, new PortalSession(session.identityKey(), session.name(), LocalDateTime.now().plusMinutes(SESSION_MINUTES)));
        return session;
    }

    private boolean isLocked(String key) {
        Deque<Long> queue = failures.get(key);
        if (queue == null) return false;
        synchronized (queue) {
            long cutoff = System.currentTimeMillis() - LOCK_MINUTES * 60_000L;
            while (!queue.isEmpty() && queue.peekFirst() < cutoff) queue.pollFirst();
            if (queue.size() < LOCK_THRESHOLD) return false;
            return System.currentTimeMillis() - queue.peekFirst() < LOCK_MINUTES * 60_000L;
        }
    }

    private void recordFailure(String key) {
        Deque<Long> queue = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(System.currentTimeMillis());
            while (queue.size() > LOCK_THRESHOLD * 3) queue.pollFirst();
        }
        if (failures.size() > 10000) failures.clear();
    }

    private void rateLimit(Map<String, Deque<Long>> hits, String key, int limit) {
        long now = System.currentTimeMillis();
        Deque<Long> queue = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > 60_000L) queue.pollFirst();
            if (queue.size() >= limit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "访问过于频繁，请稍后再试");
            queue.addLast(now);
        }
        if (hits.size() > 10000) hits.clear();
    }

    private void audit(String identityKey, String action, boolean success, String ip, String userAgent) {
        try {
            jdbcTemplate.update("""
                INSERT INTO lab_portal_access_logs (id, identity_key, action, success, ip, user_agent, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, "lpl-" + UUID.randomUUID(), identityKey, action, success ? 1 : 0, ip,
                userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length())), LocalDateTime.now());
        } catch (Exception ignored) {
            // 审计失败不影响查询
        }
    }

    private String identityKey(String type, String name, String suffix) {
        return hmac(type.toLowerCase(Locale.ROOT) + "|" + name + "|" + suffix);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "身份校验失败");
        }
    }

    private void verify(String timestamp, String signature, String body) {
        if (sharedSecret == null || sharedSecret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "门户未配置接收密钥");
        if (timestamp == null || signature == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少签名");
        long value;
        try { value = Long.parseLong(timestamp); } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "时间戳无效");
        }
        if (Math.abs(System.currentTimeMillis() - value) > INGEST_WINDOW_MILLIS) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请求已过期");
        String expected = hmac(timestamp + "." + body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "签名校验失败");
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (Exception error) {
            return LocalDate.now();
        }
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value == null || value.isBlank() ? "[]" : value);
        } catch (Exception error) {
            throw badRequest("数据格式不正确");
        }
    }

    private String text(JsonNode node) { return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    private record PortalSession(String identityKey, String name, LocalDateTime expiresAt) {}
    public record LookupResult(String sessionToken, Map<String, Object> data) {}
}
