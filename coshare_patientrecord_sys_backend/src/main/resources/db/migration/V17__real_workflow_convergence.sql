-- V17 hardens the stable clinical-record and inventory workflows for real data.
-- The migration is additive: historical rows remain available and existing URLs stay compatible.

ALTER TABLE pre_ai_encounters
  ADD COLUMN inventory_care_type VARCHAR(16) NOT NULL DEFAULT 'outpatient' AFTER route,
  ADD COLUMN care_type_locked_at VARCHAR(32) NULL AFTER inventory_care_type,
  ADD COLUMN care_transition_from_encounter_id VARCHAR(64) NULL AFTER follow_up_of_encounter_id,
  ADD COLUMN facts_revision BIGINT NOT NULL DEFAULT 0 AFTER reviewed_by_role,
  ADD COLUMN reviewed_facts_revision BIGINT NULL AFTER facts_revision,
  ADD INDEX idx_pre_ai_encounter_care_type (owning_department_id, inventory_care_type, created_at),
  ADD INDEX idx_pre_ai_encounter_care_transition (care_transition_from_encounter_id);

UPDATE pre_ai_encounters
SET inventory_care_type = CASE
      WHEN LOWER(COALESCE(NULLIF(route, ''), JSON_UNQUOTE(JSON_EXTRACT(patient_json, '$.inventoryCareType')), ''))
           IN ('inpatient', '住院') THEN 'inpatient'
      ELSE 'outpatient'
    END,
    care_type_locked_at = COALESCE(NULLIF(created_at, ''), DATE_FORMAT(CURRENT_TIMESTAMP(3), '%Y-%m-%d %H:%i:%s'));

ALTER TABLE pre_ai_stage_submissions
  ADD COLUMN requires_reconfirmation BOOLEAN NOT NULL DEFAULT FALSE AFTER returned_reason;

CREATE TABLE pre_ai_care_encounters (
  id VARCHAR(64) PRIMARY KEY,
  clinical_encounter_id VARCHAR(64) NOT NULL,
  source_care_encounter_id VARCHAR(64) NULL,
  care_type VARCHAR(16) NOT NULL,
  owning_department_id VARCHAR(64) NOT NULL,
  case_token VARCHAR(160),
  visit_date DATE NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  started_at DATETIME(3) NOT NULL,
  ended_at DATETIME(3) NULL,
  created_by VARCHAR(120),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_pre_ai_care_encounter_type (clinical_encounter_id, care_type),
  INDEX idx_pre_ai_care_encounter_weekly (owning_department_id, care_type, visit_date, status),
  INDEX idx_pre_ai_care_encounter_source (source_care_encounter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO pre_ai_care_encounters (
  id, clinical_encounter_id, source_care_encounter_id, care_type, owning_department_id,
  case_token, visit_date, status, started_at, created_by
)
SELECT CONCAT('care-', LEFT(SHA2(CONCAT(e.id, '|', e.inventory_care_type), 256), 32)),
       e.id, NULL, e.inventory_care_type, e.owning_department_id, e.case_token,
       COALESCE(STR_TO_DATE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.visitDate')), ''), '%Y-%m-%d'),
                STR_TO_DATE(LEFT(e.created_at, 10), '%Y-%m-%d'), CURRENT_DATE),
       CASE WHEN e.status = 'CANCELLED' THEN 'CANCELLED' ELSE 'ACTIVE' END,
       COALESCE(STR_TO_DATE(NULLIF(e.created_at, ''), '%Y-%m-%d %H:%i:%s'), CURRENT_TIMESTAMP(3)),
       e.created_by
FROM pre_ai_encounters e;

CREATE TABLE pre_ai_stage_revision_history (
  id VARCHAR(64) PRIMARY KEY,
  encounter_id VARCHAR(64) NOT NULL,
  stage_code VARCHAR(32) NOT NULL,
  version INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  data_json JSON NOT NULL,
  returned_reason VARCHAR(500),
  requires_reconfirmation BOOLEAN NOT NULL DEFAULT FALSE,
  changed_by VARCHAR(100),
  changed_by_role VARCHAR(64),
  change_action VARCHAR(64) NOT NULL,
  change_reason VARCHAR(500),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_pre_ai_stage_revision_history (encounter_id, stage_code, version, change_action),
  INDEX idx_pre_ai_stage_revision_encounter (encounter_id, stage_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE inventory_stage_consumption_commands
  ADD COLUMN care_type VARCHAR(16) NOT NULL DEFAULT 'outpatient' AFTER route,
  ADD COLUMN care_encounter_id VARCHAR(64) NULL AFTER care_type,
  ADD COLUMN package_id VARCHAR(64) NULL AFTER care_encounter_id,
  ADD COLUMN package_version INT NULL AFTER package_id,
  ADD INDEX idx_inventory_stage_command_care (department_id, care_type, visit_date, status);

UPDATE inventory_stage_consumption_commands
SET care_type = CASE WHEN LOWER(COALESCE(route, '')) = 'inpatient' THEN 'inpatient' ELSE 'outpatient' END;

UPDATE inventory_stage_consumption_commands c
LEFT JOIN pre_ai_care_encounters ce
  ON ce.clinical_encounter_id = c.encounter_id AND ce.care_type = c.care_type
LEFT JOIN inventory_consumption_events e ON e.command_id = c.id
LEFT JOIN inventory_packages p ON p.id = e.package_id
SET c.care_encounter_id = ce.id,
    c.package_id = e.package_id,
    c.package_version = p.version_no;

ALTER TABLE inventory_consumption_events
  ADD COLUMN care_type VARCHAR(16) NOT NULL DEFAULT 'outpatient' AFTER route,
  ADD COLUMN care_encounter_id VARCHAR(64) NULL AFTER care_type,
  ADD COLUMN package_version INT NULL AFTER package_id,
  ADD INDEX idx_inventory_consumption_care (department_id, care_type, visit_date, status);

UPDATE inventory_consumption_events
SET care_type = CASE WHEN LOWER(COALESCE(route, '')) = 'inpatient' THEN 'inpatient' ELSE 'outpatient' END;

UPDATE inventory_consumption_events e
LEFT JOIN pre_ai_care_encounters ce
  ON ce.clinical_encounter_id = e.encounter_id AND ce.care_type = e.care_type
LEFT JOIN inventory_packages p ON p.id = e.package_id
SET e.care_encounter_id = ce.id,
    e.package_version = p.version_no;

ALTER TABLE inventory_weekly_snapshots
  ADD COLUMN validity_status VARCHAR(24) NOT NULL DEFAULT 'CURRENT' AFTER status,
  ADD COLUMN invalidated_at DATETIME(3) NULL AFTER confirmed_at,
  ADD COLUMN invalidated_reason VARCHAR(1000) NULL AFTER invalidated_at,
  ADD INDEX idx_inventory_weekly_snapshot_validity (week_no, department_id, validity_status, revision);

ALTER TABLE clinic_generated_medical_records
  ADD COLUMN source_encounter_id VARCHAR(64) NULL AFTER patient_id,
  ADD COLUMN source_digest VARCHAR(64) NULL AFTER content_hash,
  ADD COLUMN source_facts_revision BIGINT NULL AFTER source_digest,
  ADD COLUMN validity_status VARCHAR(24) NOT NULL DEFAULT 'CURRENT' AFTER status,
  ADD COLUMN invalidated_at VARCHAR(32) NULL AFTER finalized_at,
  ADD COLUMN invalidated_reason VARCHAR(500) NULL AFTER invalidated_at,
  ADD COLUMN finalized_by VARCHAR(120) NULL AFTER finalized_at,
  ADD COLUMN voided_by VARCHAR(120) NULL AFTER voided_at,
  ADD INDEX idx_medical_records_source_encounter (source_encounter_id, validity_status, version);

UPDATE clinic_generated_medical_records
SET source_encounter_id = CASE
      WHEN patient_id LIKE 'preai:%' THEN SUBSTRING(patient_id, 7)
      ELSE NULL
    END,
    source_digest = NULLIF(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.sourceDigest')), ''),
    source_facts_revision = CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.sourceFactsRevision')), '') AS UNSIGNED);
