package com.coshare.patientrecord.ai.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class ClinicAiAssistantLogSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean schemaReady = false;

    public ClinicAiAssistantLogSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public synchronized void ensureSchema() {
        if (schemaReady) return;
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_ai_assistant_logs (
              id VARCHAR(128) PRIMARY KEY,
              created_at VARCHAR(32),
              assistant_type VARCHAR(32),
              status VARCHAR(32),
              operator_id VARCHAR(64),
              operator_name VARCHAR(100),
              operator_role VARCHAR(64),
              operator_department VARCHAR(100),
              page_source VARCHAR(100),
              page_path VARCHAR(255),
              model VARCHAR(128),
              latency_ms BIGINT DEFAULT 0,
              intent_category VARCHAR(64),
              prompt_preview VARCHAR(512),
              error_message VARCHAR(512),
              template_candidate BOOLEAN DEFAULT FALSE,
              raw_json LONGTEXT,
              INDEX idx_clinic_ai_logs_created (created_at),
              INDEX idx_clinic_ai_logs_type (assistant_type),
              INDEX idx_clinic_ai_logs_status (status),
              INDEX idx_clinic_ai_logs_operator (operator_role, operator_department)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS clinic_ai_prompt_templates (
              id VARCHAR(128) PRIMARY KEY,
              created_at VARCHAR(32),
              assistant_type VARCHAR(32),
              title VARCHAR(160),
              role_scope VARCHAR(64),
              source_log_id VARCHAR(128),
              raw_json LONGTEXT,
              INDEX idx_clinic_ai_templates_type (assistant_type),
              INDEX idx_clinic_ai_templates_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        ensureColumn("clinic_ai_assistant_logs", "created_at", "VARCHAR(32)");
        ensureColumn("clinic_ai_assistant_logs", "assistant_type", "VARCHAR(32)");
        ensureColumn("clinic_ai_assistant_logs", "status", "VARCHAR(32)");
        ensureColumn("clinic_ai_assistant_logs", "operator_id", "VARCHAR(64)");
        ensureColumn("clinic_ai_assistant_logs", "operator_name", "VARCHAR(100)");
        ensureColumn("clinic_ai_assistant_logs", "operator_role", "VARCHAR(64)");
        ensureColumn("clinic_ai_assistant_logs", "operator_department", "VARCHAR(100)");
        ensureColumn("clinic_ai_assistant_logs", "page_source", "VARCHAR(100)");
        ensureColumn("clinic_ai_assistant_logs", "page_path", "VARCHAR(255)");
        ensureColumn("clinic_ai_assistant_logs", "model", "VARCHAR(128)");
        ensureColumn("clinic_ai_assistant_logs", "latency_ms", "BIGINT DEFAULT 0");
        ensureColumn("clinic_ai_assistant_logs", "intent_category", "VARCHAR(64)");
        ensureColumn("clinic_ai_assistant_logs", "prompt_preview", "VARCHAR(512)");
        ensureColumn("clinic_ai_assistant_logs", "error_message", "VARCHAR(512)");
        ensureColumn("clinic_ai_assistant_logs", "template_candidate", "BOOLEAN DEFAULT FALSE");
        ensureColumn("clinic_ai_assistant_logs", "raw_json", "LONGTEXT");
        alterColumn("clinic_ai_assistant_logs", "raw_json", "LONGTEXT");
        ensureColumn("clinic_ai_prompt_templates", "created_at", "VARCHAR(32)");
        ensureColumn("clinic_ai_prompt_templates", "assistant_type", "VARCHAR(32)");
        ensureColumn("clinic_ai_prompt_templates", "title", "VARCHAR(160)");
        ensureColumn("clinic_ai_prompt_templates", "role_scope", "VARCHAR(64)");
        ensureColumn("clinic_ai_prompt_templates", "source_log_id", "VARCHAR(128)");
        ensureColumn("clinic_ai_prompt_templates", "raw_json", "LONGTEXT");
        alterColumn("clinic_ai_prompt_templates", "raw_json", "LONGTEXT");
        schemaReady = true;
    }

    public boolean tryEnsureSchema() {
        try {
            ensureSchema();
            return true;
        } catch (DataAccessException error) {
            return false;
        }
    }

    private void ensureColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (DataAccessException ignored) {
            // Existing pilot databases may already have this column.
        }
    }

    private void alterColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + definition);
        } catch (DataAccessException ignored) {
            // Schema compatibility is best-effort; querying has a fallback path in the service.
        }
    }
}
