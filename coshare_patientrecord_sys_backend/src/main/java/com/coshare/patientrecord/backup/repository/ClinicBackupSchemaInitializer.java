package com.coshare.patientrecord.backup.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicBackupSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public ClinicBackupSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_backup_config (
              config_id VARCHAR(32) PRIMARY KEY,
              backup_dir VARCHAR(1024) NOT NULL,
              enabled BOOLEAN NOT NULL DEFAULT TRUE,
              retention_policy VARCHAR(64) NOT NULL DEFAULT '7d4w12m',
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_backup_runs (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              trigger_type VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL,
              backup_dir VARCHAR(1024),
              backup_file VARCHAR(1024),
              started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              finished_at TIMESTAMP NULL,
              size_bytes BIGINT DEFAULT 0,
              message TEXT,
              manifest_json JSON NULL,
              INDEX idx_clinic_backup_runs_started (started_at),
              INDEX idx_clinic_backup_runs_status (status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }
}
