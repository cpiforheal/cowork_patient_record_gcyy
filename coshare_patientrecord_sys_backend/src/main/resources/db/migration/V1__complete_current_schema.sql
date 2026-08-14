-- Generated from the complete schema definitions that existed before Flyway adoption.
-- Empty databases execute V1; non-empty legacy databases are baselined at version 1.

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_auth_sessions (
              token_hash CHAR(64) PRIMARY KEY,
              user_id VARCHAR(64) NOT NULL,
              username VARCHAR(100) NOT NULL,
              display_name VARCHAR(100) NOT NULL,
              role VARCHAR(64) NOT NULL,
              role_label VARCHAR(100),
              department VARCHAR(100),
              must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
              expires_at TIMESTAMP(6) NOT NULL,
              created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
              revoked_at TIMESTAMP(6) NULL,
              revoke_reason VARCHAR(64) NULL,
              INDEX idx_clinic_auth_sessions_user (user_id, revoked_at),
              INDEX idx_clinic_auth_sessions_expiry (expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_login_failures (
              failure_key_hash CHAR(64) PRIMARY KEY,
              username VARCHAR(100) NOT NULL,
              remote_address VARCHAR(128) NOT NULL,
              attempts INT NOT NULL DEFAULT 0,
              locked_until TIMESTAMP(6) NULL,
              last_failed_at TIMESTAMP(6) NOT NULL,
              INDEX idx_clinic_login_failures_last_failed (last_failed_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_backup_config (
              config_id VARCHAR(32) PRIMARY KEY,
              backup_dir VARCHAR(1024) NOT NULL,
              enabled BOOLEAN NOT NULL DEFAULT TRUE,
              retention_policy VARCHAR(64) NOT NULL DEFAULT '7d4w12m',
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_record_fields (
              patient_id VARCHAR(64) PRIMARY KEY,
              fields_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_record_field_values (
              patient_id VARCHAR(64) NOT NULL,
              field_key VARCHAR(128) NOT NULL,
              field_value TEXT,
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (patient_id, field_key),
              INDEX idx_clinic_record_field_values_field (field_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_archive (
              patient_id VARCHAR(64) PRIMARY KEY,
              submitted BOOLEAN NOT NULL DEFAULT FALSE,
              version VARCHAR(100),
              generated_at VARCHAR(32),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_accounts (
              id VARCHAR(64) PRIMARY KEY,
              username VARCHAR(100),
              role VARCHAR(64),
              status VARCHAR(32),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_roles (
              id VARCHAR(64) PRIMARY KEY,
              role VARCHAR(64),
              name VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_departments (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_dictionaries (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(100),
              department VARCHAR(100),
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_template_field_rules (
              id VARCHAR(128) PRIMARY KEY,
              section_key VARCHAR(100),
              field_key VARCHAR(100),
              field_label VARCHAR(100),
              department VARCHAR(100),
              enabled BOOLEAN,
              sort_no INT DEFAULT 0,
              raw_json JSON NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_db_snapshots (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              payload_json JSON NOT NULL,
              saved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_db_meta (
              meta_key VARCHAR(64) PRIMARY KEY,
              meta_value VARCHAR(128) NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_tickets (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              business_date DATE NOT NULL,
              public_no VARCHAR(16) NOT NULL,
              visit_type VARCHAR(24) NOT NULL,
              patient_id VARCHAR(64) NOT NULL DEFAULT '',
              patient_name VARCHAR(80) NOT NULL,
              masked_name VARCHAR(80) NOT NULL,
              overall_status VARCHAR(32) NOT NULL,
              version INT NOT NULL DEFAULT 0,
              created_by VARCHAR(100) NOT NULL,
              created_by_role VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL,
              updated_at DATETIME NOT NULL,
              completed_at DATETIME NULL,
              UNIQUE KEY uq_clinic_queue_encounter (encounter_id),
              UNIQUE KEY uq_clinic_queue_public_no (business_date, public_no),
              INDEX idx_clinic_queue_status (business_date, overall_status, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_tasks (
              id VARCHAR(64) PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL,
              stage_code VARCHAR(32) NOT NULL,
              room_code VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL,
              version INT NOT NULL DEFAULT 0,
              queue_entered_at DATETIME NULL,
              called_at DATETIME NULL,
              arrived_at DATETIME NULL,
              service_started_at DATETIME NULL,
              completed_at DATETIME NULL,
              interrupted_from_status VARCHAR(32) NOT NULL DEFAULT '',
              priority_locked BOOLEAN NOT NULL DEFAULT FALSE,
              priority_reason VARCHAR(500) NOT NULL DEFAULT '',
              recall_count INT NOT NULL DEFAULT 0,
              exception_reason VARCHAR(500) NOT NULL DEFAULT '',
              updated_by VARCHAR(100) NOT NULL DEFAULT '',
              updated_at DATETIME NOT NULL,
              UNIQUE KEY uq_clinic_queue_task_stage (ticket_id, stage_code),
              INDEX idx_clinic_queue_task_dispatch (stage_code, status, priority_locked, queue_entered_at),
              INDEX idx_clinic_queue_task_room (room_code, status, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_rooms (
              room_code VARCHAR(32) PRIMARY KEY,
              room_name VARCHAR(80) NOT NULL,
              stage_code VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL,
              pause_reason VARCHAR(500) NOT NULL DEFAULT '',
              follow_up_streak INT NOT NULL DEFAULT 0,
              version INT NOT NULL DEFAULT 0,
              updated_by VARCHAR(100) NOT NULL DEFAULT '',
              updated_at DATETIME NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_announcements (
              id VARCHAR(64) PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL,
              task_id VARCHAR(64) NOT NULL,
              public_no VARCHAR(16) NOT NULL,
              stage_code VARCHAR(32) NOT NULL,
              room_name VARCHAR(80) NOT NULL,
              content VARCHAR(500) NOT NULL,
              status VARCHAR(24) NOT NULL,
              play_count INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL,
              expires_at DATETIME NOT NULL,
              played_at DATETIME NULL,
              INDEX idx_clinic_queue_announcement (status, created_at),
              INDEX idx_clinic_queue_announcement_expiry (status, expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_emergencies (
              id VARCHAR(64) PRIMARY KEY,
              room_code VARCHAR(32) NOT NULL,
              status VARCHAR(24) NOT NULL,
              reason VARCHAR(500) NOT NULL,
              started_by VARCHAR(100) NOT NULL,
              started_by_role VARCHAR(64) NOT NULL,
              started_at DATETIME NOT NULL,
              ended_by VARCHAR(100) NOT NULL DEFAULT '',
              ended_at DATETIME NULL,
              INDEX idx_clinic_queue_emergency (room_code, status, started_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_audit_logs (
              id VARCHAR(64) PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL DEFAULT '',
              task_id VARCHAR(64) NOT NULL DEFAULT '',
              room_code VARCHAR(32) NOT NULL DEFAULT '',
              action_code VARCHAR(64) NOT NULL,
              from_status VARCHAR(64) NOT NULL DEFAULT '',
              to_status VARCHAR(64) NOT NULL DEFAULT '',
              operator_id VARCHAR(64) NOT NULL DEFAULT '',
              operator_name VARCHAR(100) NOT NULL,
              operator_role VARCHAR(64) NOT NULL,
              detail VARCHAR(1000) NOT NULL DEFAULT '',
              created_at DATETIME NOT NULL,
              INDEX idx_clinic_queue_audit_ticket (ticket_id, created_at),
              INDEX idx_clinic_queue_audit_room (room_code, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_print_templates (
              template_code VARCHAR(64) PRIMARY KEY,
              config_json JSON NOT NULL,
              updated_by VARCHAR(100) NOT NULL,
              updated_at DATETIME NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_print_terminals (
              terminal_id VARCHAR(80) PRIMARY KEY,
              terminal_name VARCHAR(120) NOT NULL,
              printer_name VARCHAR(200) NOT NULL DEFAULT '',
              agent_version VARCHAR(40) NOT NULL DEFAULT '',
              status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
              last_seen_at DATETIME NOT NULL,
              registered_by VARCHAR(100) NOT NULL,
              updated_at DATETIME NOT NULL,
              INDEX idx_clinic_queue_print_terminal_status (status, last_seen_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_queue_print_tasks (
              id VARCHAR(64) PRIMARY KEY,
              ticket_id VARCHAR(64) NOT NULL,
              client_request_id VARCHAR(100) NOT NULL,
              terminal_id VARCHAR(80) NOT NULL,
              printer_name VARCHAR(200) NOT NULL DEFAULT '',
              status VARCHAR(24) NOT NULL,
              print_type VARCHAR(24) NOT NULL,
              reprint_reason VARCHAR(500) NOT NULL DEFAULT '',
              attempt_count INT NOT NULL DEFAULT 0,
              payload_json JSON NOT NULL,
              execution_token VARCHAR(80) NOT NULL,
              error_message VARCHAR(1000) NOT NULL DEFAULT '',
              created_by VARCHAR(100) NOT NULL,
              created_by_role VARCHAR(64) NOT NULL,
              created_at DATETIME NOT NULL,
              started_at DATETIME NULL,
              completed_at DATETIME NULL,
              updated_at DATETIME NOT NULL,
              UNIQUE KEY uq_clinic_queue_print_token (execution_token),
              UNIQUE KEY uq_clinic_queue_print_client_request (client_request_id),
              INDEX idx_clinic_queue_print_ticket (ticket_id, created_at),
              INDEX idx_clinic_queue_print_terminal (terminal_id, status, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_encounters (
              id VARCHAR(64) PRIMARY KEY,
              source_patient_id VARCHAR(64),
              patient_case_id VARCHAR(64),
              visit_no INT NOT NULL DEFAULT 1,
              follow_up_of_encounter_id VARCHAR(64),
              case_token VARCHAR(64) NOT NULL UNIQUE,
              route VARCHAR(32),
              treatment_path VARCHAR(32),
              status VARCHAR(32) NOT NULL,
              current_stage VARCHAR(32) NOT NULL,
              patient_json JSON NOT NULL,
              visit_meta_json JSON NULL,
              legacy_reference_json JSON NULL,
              duty_assignments_json JSON NULL,
              reviewed_at VARCHAR(32),
              reviewed_by VARCHAR(100),
              reviewed_by_role VARCHAR(64),
              created_at VARCHAR(32) NOT NULL,
              updated_at VARCHAR(32) NOT NULL,
              created_by VARCHAR(100),
              created_by_role VARCHAR(64),
              INDEX idx_pre_ai_encounter_source_patient (source_patient_id),
              INDEX idx_pre_ai_encounter_patient_case (patient_case_id, visit_no),
              INDEX idx_pre_ai_encounter_status (status),
              INDEX idx_pre_ai_encounter_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_patient_cases (
              id VARCHAR(64) PRIMARY KEY,
              source_patient_id VARCHAR(64),
              patient_json JSON NOT NULL,
              created_at VARCHAR(32) NOT NULL,
              updated_at VARCHAR(32) NOT NULL,
              INDEX idx_pre_ai_patient_source (source_patient_id),
              INDEX idx_pre_ai_patient_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_stage_submissions (
              encounter_id VARCHAR(64) NOT NULL,
              stage_code VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL,
              version INT NOT NULL DEFAULT 0,
              data_json JSON NOT NULL,
              returned_reason VARCHAR(500),
              submitted_by VARCHAR(100),
              submitted_by_role VARCHAR(64),
              completed_at VARCHAR(32),
              updated_at VARCHAR(32) NOT NULL,
              PRIMARY KEY (encounter_id, stage_code),
              INDEX idx_pre_ai_stage_status (stage_code, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_auxiliary_tasks (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              task_type VARCHAR(32) NOT NULL,
              title VARCHAR(255),
              owner_role VARCHAR(64) NOT NULL,
              required_before_export BOOLEAN NOT NULL DEFAULT FALSE,
              status VARCHAR(32) NOT NULL,
              data_json JSON NOT NULL,
              version INT NOT NULL DEFAULT 0,
              completed_at VARCHAR(32),
              updated_at VARCHAR(32) NOT NULL,
              updated_by VARCHAR(100),
              updated_by_role VARCHAR(64),
              completed_by VARCHAR(100),
              completed_by_role VARCHAR(64),
              created_at VARCHAR(32) NOT NULL,
              created_by VARCHAR(100),
              INDEX idx_pre_ai_aux_encounter (encounter_id),
              INDEX idx_pre_ai_aux_owner_status (owner_role, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_attachments (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              stage_code VARCHAR(32),
              task_id VARCHAR(64),
              file_name VARCHAR(255),
              storage_path VARCHAR(512) NOT NULL,
              mime_type VARCHAR(128),
              file_size BIGINT DEFAULT 0,
              sha256 VARCHAR(128),
              description VARCHAR(500),
              captured_at VARCHAR(32),
              uploader VARCHAR(100),
              uploader_role VARCHAR(64),
              batch_id VARCHAR(64),
              batch_name VARCHAR(255),
              relative_path VARCHAR(700),
              sequence_no INT,
              status VARCHAR(32) NOT NULL,
              created_at VARCHAR(32) NOT NULL,
              INDEX idx_pre_ai_attachment_encounter (encounter_id),
              INDEX idx_pre_ai_attachment_task (task_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_lab_reports (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              template_id VARCHAR(100) NOT NULL,
              template_name VARCHAR(255) NOT NULL,
              report_date VARCHAR(32) NOT NULL,
              remark VARCHAR(1000),
              metrics_json JSON NOT NULL,
              version INT NOT NULL DEFAULT 1,
              status VARCHAR(32) NOT NULL,
              saved_by VARCHAR(100),
              saved_by_role VARCHAR(64),
              saved_at VARCHAR(32) NOT NULL,
              INDEX idx_pre_ai_lab_encounter (encounter_id, status),
              INDEX idx_pre_ai_lab_template (encounter_id, template_id, report_date, version)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_diagnoses (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              diagnosis_type VARCHAR(64) NOT NULL,
              diagnosis_text VARCHAR(1000) NOT NULL,
              sort_no INT NOT NULL DEFAULT 0,
              source_stage VARCHAR(32) NOT NULL,
              updated_at VARCHAR(32) NOT NULL,
              INDEX idx_pre_ai_diagnosis_encounter (encounter_id),
              INDEX idx_pre_ai_diagnosis_type (diagnosis_type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_audit_logs (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              action VARCHAR(100) NOT NULL,
              stage_code VARCHAR(32),
              operator VARCHAR(100),
              operator_role VARCHAR(64),
              detail VARCHAR(1000),
              created_at VARCHAR(32) NOT NULL,
              INDEX idx_pre_ai_audit_encounter (encounter_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_exports (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              version INT NOT NULL,
              status VARCHAR(32) NOT NULL,
              case_token VARCHAR(64) NOT NULL,
              file_name VARCHAR(255) NOT NULL,
              file_path VARCHAR(700) NOT NULL,
              source_snapshot JSON NOT NULL,
              masked_snapshot JSON NOT NULL,
              generated_by VARCHAR(100),
              generated_by_role VARCHAR(64),
              generated_at VARCHAR(32) NOT NULL,
              INDEX idx_pre_ai_export_encounter (encounter_id, version)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tcm_pharmacy_prescriptions (
              id VARCHAR(64) PRIMARY KEY,
              prescription_no VARCHAR(64) NOT NULL UNIQUE,
              version_no INT NOT NULL DEFAULT 1,
              patient_id VARCHAR(64) NOT NULL DEFAULT '',
              patient_name VARCHAR(80) NOT NULL,
              masked_name VARCHAR(80) NOT NULL,
              visit_no VARCHAR(64) NOT NULL DEFAULT '',
              doctor_name VARCHAR(80) NOT NULL,
              dispense_type VARCHAR(32) NOT NULL,
              prescription_status VARCHAR(32) NOT NULL,
              charge_status VARCHAR(32) NOT NULL,
              review_status VARCHAR(32) NOT NULL,
              dispensing_status VARCHAR(32) NOT NULL,
              decoction_status VARCHAR(32) NOT NULL,
              pickup_status VARCHAR(32) NOT NULL,
              pickup_no VARCHAR(32) NOT NULL DEFAULT '',
              amount DECIMAL(12,2) NOT NULL DEFAULT 0,
              herb_count INT NOT NULL DEFAULT 0,
              dose_count INT NOT NULL DEFAULT 1,
              items_json LONGTEXT NOT NULL,
              requirements_json LONGTEXT NOT NULL,
              exception_reason VARCHAR(500) NOT NULL DEFAULT '',
              created_by VARCHAR(80) NOT NULL,
              updated_by VARCHAR(80) NOT NULL,
              submitted_at DATETIME NULL,
              charged_at DATETIME NULL,
              reviewed_at DATETIME NULL,
              ready_at DATETIME NULL,
              collected_at DATETIME NULL,
              created_at DATETIME NOT NULL,
              updated_at DATETIME NOT NULL,
              INDEX idx_tcm_status (prescription_status, updated_at),
              INDEX idx_tcm_pickup (pickup_status, ready_at),
              INDEX idx_tcm_patient (patient_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tcm_pharmacy_audit_logs (
              id VARCHAR(64) PRIMARY KEY,
              prescription_id VARCHAR(64) NOT NULL,
              action_code VARCHAR(64) NOT NULL,
              from_status VARCHAR(64) NOT NULL DEFAULT '',
              to_status VARCHAR(64) NOT NULL DEFAULT '',
              operator_id VARCHAR(64) NOT NULL DEFAULT '',
              operator_name VARCHAR(80) NOT NULL,
              operator_role VARCHAR(40) NOT NULL,
              detail VARCHAR(1000) NOT NULL DEFAULT '',
              created_at DATETIME NOT NULL,
              INDEX idx_tcm_audit (prescription_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tcm_pharmacy_announcements (
              id VARCHAR(64) PRIMARY KEY,
              prescription_id VARCHAR(64) NOT NULL,
              pickup_no VARCHAR(32) NOT NULL,
              masked_name VARCHAR(80) NOT NULL,
              content VARCHAR(500) NOT NULL,
              status VARCHAR(24) NOT NULL,
              play_count INT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL,
              played_at DATETIME NULL,
              INDEX idx_tcm_announcement (status, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
