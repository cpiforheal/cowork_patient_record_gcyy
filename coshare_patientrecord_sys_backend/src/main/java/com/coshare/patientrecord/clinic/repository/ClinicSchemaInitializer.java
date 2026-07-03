package com.coshare.patientrecord.clinic.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
class ClinicSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    ClinicSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_patients (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(100) NOT NULL,
              visit_no VARCHAR(100),
              visit_date VARCHAR(32),
              visit_type VARCHAR(32),
              doctor VARCHAR(100),
              current_stage VARCHAR(100),
              completed_count INT DEFAULT 0,
              progress_percent INT DEFAULT 0,
              status VARCHAR(100),
              risk_type VARCHAR(32),
              created_at VARCHAR(32),
              updated_at VARCHAR(32),
              encounter_count INT DEFAULT 1,
              encounter_history_json JSON NULL,
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_record_fields (
              patient_id VARCHAR(64) PRIMARY KEY,
              fields_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_patient_encounters (
              id VARCHAR(128) PRIMARY KEY,
              patient_id VARCHAR(64) NOT NULL,
              visit_no VARCHAR(100),
              visit_date VARCHAR(32),
              visit_type VARCHAR(32),
              doctor VARCHAR(100),
              created_at VARCHAR(32),
              sort_no INT DEFAULT 0,
              raw_json JSON NOT NULL,
              INDEX idx_clinic_patient_encounters_patient (patient_id),
              INDEX idx_clinic_patient_encounters_date (visit_date),
              INDEX idx_clinic_patient_encounters_visit_no (visit_no)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_record_field_values (
              patient_id VARCHAR(64) NOT NULL,
              field_key VARCHAR(128) NOT NULL,
              field_value TEXT,
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (patient_id, field_key),
              INDEX idx_clinic_record_field_values_field (field_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_archive (
              patient_id VARCHAR(64) PRIMARY KEY,
              submitted BOOLEAN NOT NULL DEFAULT FALSE,
              version VARCHAR(100),
              generated_at VARCHAR(32),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_documents (
              document_key VARCHAR(128) PRIMARY KEY,
              patient_id VARCHAR(64) NOT NULL,
              file_name VARCHAR(255),
              type VARCHAR(100),
              type_label VARCHAR(100),
              department VARCHAR(100),
              status VARCHAR(32),
              storage_path VARCHAR(512),
              url VARCHAR(512),
              uploaded_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_clinic_documents_patient (patient_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_accounts (
              id VARCHAR(64) PRIMARY KEY,
              username VARCHAR(100),
              role VARCHAR(64),
              status VARCHAR(32),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_roles (
              id VARCHAR(64) PRIMARY KEY,
              role VARCHAR(64),
              name VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_departments (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_dictionaries (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(100),
              department VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_template_field_rules (
              id VARCHAR(128) PRIMARY KEY,
              section_key VARCHAR(100),
              field_key VARCHAR(100),
              field_label VARCHAR(100),
              department VARCHAR(100),
              enabled BOOLEAN,
              sort_no INT DEFAULT 0,
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_audit_logs (
              id VARCHAR(128) PRIMARY KEY,
              time VARCHAR(32),
              operator VARCHAR(100),
              role VARCHAR(64),
              patient VARCHAR(100),
              patient_id VARCHAR(64),
              module VARCHAR(64),
              action VARCHAR(100),
              result VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_clinic_audit_logs_patient (patient_id),
              INDEX idx_clinic_audit_logs_time (time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_db_snapshots (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              payload_json JSON NOT NULL,
              saved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_db_meta (
              meta_key VARCHAR(64) PRIMARY KEY,
              meta_value VARCHAR(128) NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }
}
