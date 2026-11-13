package com.coshare.patientrecord.preai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class LabReportPushWorker {
    private static final Logger log = LoggerFactory.getLogger(LabReportPushWorker.class);
    private static final int[] BACKOFF = {1, 2, 4, 8, 16, 30, 60, 60};
    private final JdbcTemplate jdbcTemplate;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicBoolean running = new AtomicBoolean();
    @Value("${clinic.lab-portal.enabled:false}") private boolean enabled;
    @Value("${clinic.lab-portal.ingest-url:}") private String ingestUrl;
    @Value("${clinic.lab-portal.shared-secret:}") private String secret;

    public LabReportPushWorker(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Scheduled(fixedDelay = 30000)
    public void drain() {
        if (!enabled || ingestUrl == null || ingestUrl.isBlank() || secret == null || secret.isBlank() || !running.compareAndSet(false, true)) return;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT encounter_id, payload_json, attempts
                FROM clinic_lab_portal_sync
                WHERE push_status IN ('PENDING','FAILED')
                  AND attempts < 8 AND payload_json IS NOT NULL
                  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                ORDER BY updated_at LIMIT 20
                """, LocalDateTime.now());
            for (Map<String, Object> row : rows) push(row);
        } catch (Exception error) { log.warn("lab portal push sweep failed", error); }
        finally { running.set(false); }
    }

    private void push(Map<String, Object> row) {
        String id = String.valueOf(row.get("encounter_id"));
        String body = String.valueOf(row.get("payload_json"));
        int attempts = row.get("attempts") instanceof Number n ? n.intValue() : 0;
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            HttpRequest request = HttpRequest.newBuilder(URI.create(ingestUrl))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Timestamp", timestamp)
                .header("X-Signature", hmac(timestamp + "." + body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                jdbcTemplate.update("UPDATE clinic_lab_portal_sync SET push_status='SENT', attempts=?, last_error=NULL, next_attempt_at=NULL WHERE encounter_id=?", attempts + 1, id);
            } else fail(id, attempts, "HTTP " + response.statusCode());
        } catch (Exception error) { fail(id, attempts, error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }

    private void fail(String id, int attempts, String message) {
        int next = attempts + 1;
        LocalDateTime retry = next >= 8 ? null : LocalDateTime.now().plusMinutes(BACKOFF[Math.min(next - 1, BACKOFF.length - 1)]);
        jdbcTemplate.update("UPDATE clinic_lab_portal_sync SET push_status=?, attempts=?, next_attempt_at=?, last_error=? WHERE encounter_id=?", next >= 8 ? "FAILED" : "PENDING", next, retry, message == null ? "" : message.substring(0, Math.min(500, message.length())), id);
    }

    private String hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) result.append(String.format("%02x", item));
        return result.toString();
    }
}
