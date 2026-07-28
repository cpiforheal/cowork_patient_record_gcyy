-- V13 introduces the additive medical-record document workflow.
-- Existing clinic-api endpoints and clinic_generated_medical_records rows remain authoritative
-- until the v2 read path is explicitly enabled.

-- The deployed baseline was checked for duplicate (patient_id, version) pairs before this
-- migration was authored. This constraint closes the legacy MAX(version) race at database level.
ALTER TABLE clinic_generated_medical_records
  ADD UNIQUE KEY uq_medical_record_scope_version (patient_id, version);

CREATE TABLE clinic_medical_record_document_assets (
  id VARCHAR(64) PRIMARY KEY,
  scope_id VARCHAR(160) NOT NULL,
  patient_id VARCHAR(64),
  encounter_id VARCHAR(64),
  asset_type VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  original_file_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(700) NOT NULL,
  mime_type VARCHAR(160) NOT NULL DEFAULT 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  file_size BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  parent_asset_id VARCHAR(64),
  media_type_verified BOOLEAN NOT NULL DEFAULT FALSE,
  package_verified BOOLEAN NOT NULL DEFAULT FALSE,
  metadata_json JSON NOT NULL,
  created_by VARCHAR(120) NOT NULL,
  created_by_role VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  retired_at DATETIME(3),
  UNIQUE KEY uq_medical_record_asset_storage_path (storage_path),
  INDEX idx_medical_record_asset_scope_type (scope_id, asset_type, created_at),
  INDEX idx_medical_record_asset_hash (sha256),
  INDEX idx_medical_record_asset_parent (parent_asset_id),
  CONSTRAINT fk_medical_record_asset_parent
    FOREIGN KEY (parent_asset_id) REFERENCES clinic_medical_record_document_assets(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_medical_record_sanitization_reports (
  id VARCHAR(64) PRIMARY KEY,
  source_asset_id VARCHAR(64) NOT NULL,
  sanitized_asset_id VARCHAR(64),
  inspection_status VARCHAR(24) NOT NULL,
  decision VARCHAR(24) NOT NULL,
  highest_risk_level VARCHAR(16) NOT NULL DEFAULT 'NONE',
  sanitizer_version VARCHAR(64) NOT NULL,
  zip_entry_count INT NOT NULL DEFAULT 0,
  compressed_size BIGINT NOT NULL DEFAULT 0,
  uncompressed_size BIGINT NOT NULL DEFAULT 0,
  removed_part_count INT NOT NULL DEFAULT 0,
  removed_relationship_count INT NOT NULL DEFAULT 0,
  external_relationship_count INT NOT NULL DEFAULT 0,
  duplicate_entry_count INT NOT NULL DEFAULT 0,
  findings_json JSON NOT NULL,
  package_validation_json JSON NOT NULL,
  failure_stage VARCHAR(48),
  failure_code VARCHAR(64),
  failure_message VARCHAR(1000),
  inspected_by VARCHAR(120) NOT NULL,
  inspected_by_role VARCHAR(64) NOT NULL,
  inspected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_medical_record_sanitized_asset (sanitized_asset_id),
  INDEX idx_medical_record_sanitization_source (source_asset_id, inspected_at),
  INDEX idx_medical_record_sanitization_decision (decision, inspected_at),
  CONSTRAINT fk_medical_record_sanitization_source
    FOREIGN KEY (source_asset_id) REFERENCES clinic_medical_record_document_assets(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_medical_record_sanitization_output
    FOREIGN KEY (sanitized_asset_id) REFERENCES clinic_medical_record_document_assets(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_medical_record_generation_tasks (
  id VARCHAR(64) PRIMARY KEY,
  scope_id VARCHAR(160) NOT NULL,
  patient_id VARCHAR(64),
  encounter_id VARCHAR(64),
  source_record_id VARCHAR(128) NOT NULL,
  source_asset_id VARCHAR(64) NOT NULL,
  sanitized_asset_id VARCHAR(64),
  sanitization_report_id VARCHAR(64) NOT NULL,
  output_asset_id VARCHAR(64),
  result_record_id VARCHAR(128),
  status VARCHAR(24) NOT NULL,
  current_stage VARCHAR(48) NOT NULL,
  mapping_mode VARCHAR(32) NOT NULL DEFAULT 'LEGACY_ORDINAL',
  prompt_text MEDIUMTEXT,
  model_name VARCHAR(128),
  request_json JSON NOT NULL,
  result_json JSON,
  attempt_count INT NOT NULL DEFAULT 1,
  retry_of_task_id VARCHAR(64),
  error_code VARCHAR(64),
  error_message VARCHAR(1000),
  created_by VARCHAR(120) NOT NULL,
  created_by_role VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at DATETIME(3),
  finished_at DATETIME(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_medical_record_task_scope (scope_id, created_at),
  INDEX idx_medical_record_task_status (status, updated_at),
  INDEX idx_medical_record_task_retry (retry_of_task_id),
  INDEX idx_medical_record_task_result (result_record_id),
  CONSTRAINT fk_medical_record_task_source_asset
    FOREIGN KEY (source_asset_id) REFERENCES clinic_medical_record_document_assets(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_medical_record_task_sanitized_asset
    FOREIGN KEY (sanitized_asset_id) REFERENCES clinic_medical_record_document_assets(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_medical_record_task_report
    FOREIGN KEY (sanitization_report_id) REFERENCES clinic_medical_record_sanitization_reports(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_medical_record_task_output_asset
    FOREIGN KEY (output_asset_id) REFERENCES clinic_medical_record_document_assets(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_medical_record_task_result_record
    FOREIGN KEY (result_record_id) REFERENCES clinic_generated_medical_records(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_medical_record_task_retry
    FOREIGN KEY (retry_of_task_id) REFERENCES clinic_medical_record_generation_tasks(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_medical_record_node_mappings (
  id VARCHAR(64) PRIMARY KEY,
  task_id VARCHAR(64) NOT NULL,
  sequence_no INT NOT NULL,
  source_node_key VARCHAR(255) NOT NULL,
  target_node_key VARCHAR(255),
  source_locator_type VARCHAR(32) NOT NULL,
  source_locator VARCHAR(1000) NOT NULL,
  target_locator_type VARCHAR(32),
  target_locator VARCHAR(1000),
  source_content_hash VARCHAR(64),
  target_content_hash VARCHAR(64),
  mapping_mode VARCHAR(32) NOT NULL,
  mapping_status VARCHAR(24) NOT NULL,
  confidence DECIMAL(5,4),
  before_preview MEDIUMTEXT,
  after_preview MEDIUMTEXT,
  metadata_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_medical_record_task_node_sequence (task_id, sequence_no),
  UNIQUE KEY uq_medical_record_task_source_node (task_id, source_node_key),
  INDEX idx_medical_record_node_status (task_id, mapping_status),
  CONSTRAINT fk_medical_record_node_task
    FOREIGN KEY (task_id) REFERENCES clinic_medical_record_generation_tasks(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_medical_record_task_events (
  id VARCHAR(64) PRIMARY KEY,
  task_id VARCHAR(64) NOT NULL,
  sequence_no INT NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  stage VARCHAR(48) NOT NULL,
  from_status VARCHAR(24),
  to_status VARCHAR(24),
  message VARCHAR(1000),
  detail_json JSON NOT NULL,
  operator_name VARCHAR(120),
  operator_role VARCHAR(64),
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_medical_record_task_event_sequence (task_id, sequence_no),
  INDEX idx_medical_record_task_event_time (task_id, occurred_at),
  CONSTRAINT fk_medical_record_event_task
    FOREIGN KEY (task_id) REFERENCES clinic_medical_record_generation_tasks(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE clinic_medical_record_version_assets (
  id VARCHAR(64) PRIMARY KEY,
  record_id VARCHAR(128) NOT NULL,
  asset_id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64),
  asset_role VARCHAR(32) NOT NULL,
  linked_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  linked_by VARCHAR(120) NOT NULL,
  UNIQUE KEY uq_medical_record_version_asset_role (record_id, asset_role),
  UNIQUE KEY uq_medical_record_version_asset (record_id, asset_id),
  INDEX idx_medical_record_version_asset_asset (asset_id),
  INDEX idx_medical_record_version_asset_task (task_id),
  CONSTRAINT fk_medical_record_version_asset_record
    FOREIGN KEY (record_id) REFERENCES clinic_generated_medical_records(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_medical_record_version_asset_asset
    FOREIGN KEY (asset_id) REFERENCES clinic_medical_record_document_assets(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_medical_record_version_asset_task
    FOREIGN KEY (task_id) REFERENCES clinic_medical_record_generation_tasks(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
