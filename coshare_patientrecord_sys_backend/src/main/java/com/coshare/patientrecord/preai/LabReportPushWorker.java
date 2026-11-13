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
    private static final int MAX_ATTEMPTS = 8;
    private static final int[] BACKOFF_MINUTES = {1, 2, 4, 8, 16, 30, 60, 60};

    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${clinic.lab-portal.enabled:false}")
    private boolean enabled;

    @Value("${clinic.lab-portal.ingest-url:}")
    private String ingestUrl;

    @Value("${clinic.lab-portal.shared-secret:}")
    private String sharedSecret;

    public LabReportPushWorker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelay = 30_000)
    public void drain() {
        if (!enabled || ingestUrl == null || ingestUrl.isBlank() || sharedSecret == null || sharedSecret.isBlank()) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, token_hash, payload_json, revoked_at, attempts
                FROM clinic_lab_portal_publications
                WHERE push_status IN ('PENDING', 'FAILED')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                  AND (revoked_at IS NOT NULL OR expires_at > ?)
                ORDER BY published_at
                LIMIT 20
                """, LocalDateTime.now(), LocalDateTime.now());
            for (Map<String, Object> row : rows) {
                push(row);
            }
        } catch (Exception error) {
            log.warn("lab report push sweep failed", error);
        } finally {
            running.set(false);
        }
    }

    private void push(Map<String, Object> row) {
        String id = String.valueOf(row.get("id"));
        String tokenHash = String.valueOf(row.get("token_hash"));
        boolean revoked = row.get("revoked_at") != null;
        String body = revoked
            ? "{\"type\":\"REVOKE\",\"tokenHash\":\"" + tokenHash + "\"}"
            : String.valueOf(row.get("payload_json"));
        int attempts = row.get("attempts") == null ? 0 : ((Number) row.get("attempts")).intValue();
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ingestUrl))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Timestamp", timestamp)
                .header("X-Signature", sign(timestamp + "." + body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                jdbcTemplate.update("""
                    UPDATE clinic_lab_portal_publications
                    SET push_status = 'SENT', attempts = ?, last_error = NULL, next_attempt_at = NULL
                    WHERE id = ?
                    """, attempts + 1, id);
                return;
            }
            fail(id, attempts, "HTTP " + response.statusCode());
        } catch (Exception error) {
            fail(id, attempts, error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
    }

    private void fail(String id, int attempts, String message) {
        int nextAttempts = attempts + 1;
        boolean exhausted = nextAttempts >= MAX_ATTEMPTS;
        LocalDateTime next = exhausted ? null : LocalDateTime.now().plusMinutes(BACKOFF_MINUTES[Math.min(nextAttempts - 1, BACKOFF_MINUTES.length - 1)]);
        jdbcTemplate.update("""
            UPDATE clinic_lab_portal_publications
            SET push_status = ?, attempts = ?, next_attempt_at = ?, last_error = ?
            WHERE id = ?
            """, exhausted ? "FAILED" : "PENDING", nextAttempts, next, truncate(message), id);
        log.warn("lab report push failed id={} attempts={} message={}", id, nextAttempts, message);
    }

    private String sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte item : digest) builder.append(String.format("%02x", item));
        return builder.toString();
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
