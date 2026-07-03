package com.coshare.patientrecord.medicalrecord.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MedicalRecordSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public MedicalRecordSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_generated_medical_records (
              id VARCHAR(128) PRIMARY KEY,
              patient_id VARCHAR(64) NOT NULL,
              version INT NOT NULL,
              status VARCHAR(32) NOT NULL,
              content MEDIUMTEXT NOT NULL,
              content_hash VARCHAR(128),
              model VARCHAR(128),
              operator VARCHAR(100),
              operator_role VARCHAR(64),
              generated_at VARCHAR(32),
              finalized_at VARCHAR(32),
              voided_at VARCHAR(32),
              void_reason VARCHAR(500),
              source_snapshot JSON NOT NULL,
              raw_json JSON NOT NULL,
              INDEX idx_medical_records_patient (patient_id),
              INDEX idx_medical_records_status (status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }
}
