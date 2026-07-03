package com.coshare.patientrecord.ai.repository;

import com.coshare.patientrecord.ai.entity.StoredAiConfig;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicAiConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClinicAiConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StoredAiConfig readStoredConfig(String configId, String fallbackModel) {
        List<StoredAiConfig> rows = jdbcTemplate.query(
            "SELECT base_url, api_key_cipher, model, resource_id, voice_type, speed_ratio, enabled, updated_at, updated_by FROM clinic_ai_config WHERE config_id = ? LIMIT 1",
            (rs, rowNum) -> new StoredAiConfig(
                safe(rs.getString("base_url")),
                safe(rs.getString("api_key_cipher")),
                safe(rs.getString("model")).isBlank() ? fallbackModel : safe(rs.getString("model")),
                safe(rs.getString("resource_id")),
                safe(rs.getString("voice_type")),
                normalizeSpeedRatio(rs.getObject("speed_ratio")),
                rs.getBoolean("enabled"),
                safe(rs.getString("updated_at")),
                safe(rs.getString("updated_by"))
            ),
            configId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveConfig(
        String configId,
        String baseUrl,
        String apiKeyCipher,
        String model,
        boolean enabled,
        String updatedAt,
        String updatedBy,
        String resourceId,
        String voiceType,
        double speedRatio
    ) {
        jdbcTemplate.update("""
            INSERT INTO clinic_ai_config (config_id, base_url, api_key_cipher, model, enabled, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              base_url = VALUES(base_url),
              api_key_cipher = VALUES(api_key_cipher),
              model = VALUES(model),
              enabled = VALUES(enabled),
              updated_at = VALUES(updated_at),
              updated_by = VALUES(updated_by)
            """, configId, baseUrl, apiKeyCipher, model, enabled, updatedAt, updatedBy);
        jdbcTemplate.update(
            "UPDATE clinic_ai_config SET resource_id = ?, voice_type = ?, speed_ratio = ? WHERE config_id = ?",
            resourceId,
            voiceType,
            speedRatio,
            configId
        );
    }

    private static String safe(Object value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private static double normalizeSpeedRatio(Object value) {
        try {
            double ratio = Double.parseDouble(String.valueOf(value == null ? "1.0" : value));
            if (ratio < 0.5) return 0.5;
            if (ratio > 2.0) return 2.0;
            return ratio;
        } catch (Exception ignored) {
            return 1.0;
        }
    }
}
