package com.coshare.patientrecord.ai.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicAiDocumentSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public ClinicAiDocumentSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_generated_ai_documents (
              id VARCHAR(128) PRIMARY KEY,
              title VARCHAR(255) NOT NULL,
              doc_type VARCHAR(64) NOT NULL,
              file_name VARCHAR(255) NOT NULL,
              file_path VARCHAR(1000) NOT NULL,
              content_hash VARCHAR(128),
              operator VARCHAR(100),
              operator_role VARCHAR(64),
              generated_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_ai_documents_generated_at (generated_at),
              INDEX idx_ai_documents_operator (operator)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }
}
