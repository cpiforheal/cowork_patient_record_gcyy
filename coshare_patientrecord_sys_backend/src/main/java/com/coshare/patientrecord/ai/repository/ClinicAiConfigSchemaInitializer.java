package com.coshare.patientrecord.ai.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicAiConfigSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public ClinicAiConfigSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_ai_config (
              config_id VARCHAR(32) PRIMARY KEY,
              base_url VARCHAR(1024),
              api_key_cipher TEXT,
              model VARCHAR(128),
              resource_id VARCHAR(256),
              voice_type VARCHAR(128),
              speed_ratio DECIMAL(4,2),
              enabled BOOLEAN NOT NULL DEFAULT TRUE,
              updated_at VARCHAR(32),
              updated_by VARCHAR(100)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        ensureColumn("resource_id", "VARCHAR(256)");
        ensureColumn("voice_type", "VARCHAR(128)");
        ensureColumn("speed_ratio", "DECIMAL(4,2)");
    }

    private void ensureColumn(String columnName, String definition) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'clinic_ai_config'
              AND COLUMN_NAME = ?
            """,
            Integer.class,
            columnName
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE clinic_ai_config ADD COLUMN " + columnName + " " + definition);
        }
    }
}
