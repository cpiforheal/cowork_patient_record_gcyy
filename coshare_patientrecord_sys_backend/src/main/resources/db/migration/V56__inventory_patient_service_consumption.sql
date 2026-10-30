-- Patient-bound consumable traceability: service instances, patient documents,
-- approval batches and correction documents.

-- Canonical inventory department catalogue. Existing clinical department IDs are
-- reused where they already represent the same operational department; departments
-- that only exist in the inventory workflow get a stable inv-* identifier.
CREATE TABLE inventory_departments (
  id VARCHAR(64) PRIMARY KEY,
  department_key VARCHAR(64) NOT NULL,
  name VARCHAR(120) NOT NULL,
  clinic_department_id VARCHAR(64) NULL,
  display_order INT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_department_key (department_key),
  UNIQUE KEY uk_inventory_department_name (name),
  INDEX idx_inventory_department_clinic (clinic_department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_departments (id, department_key, name, clinic_department_id, display_order) VALUES
  ('inv-physiotherapy', 'physiotherapy', '理疗室', NULL, 1),
  ('lab', 'laboratory', '检验科', 'lab', 2),
  ('nursing', 'nursing', '护理部', 'nursing', 3),
  ('inv-tcm', 'tcm', '中医科', NULL, 4),
  ('inv-operating', 'operating', '手术室', NULL, 5),
  ('inv-anesthesia', 'anesthesia', '麻醉室', NULL, 6),
  ('dept-1787989927868', 'endoscopy', '胃肠镜', 'dept-1787989927868', 7),
  ('inspection', 'inspection', '检查室', 'inspection', 8),
  ('inv-logistics', 'logistics', '后勤', NULL, 9),
  ('inv-western-pharmacy', 'western-pharmacy', '西药房', NULL, 10),
  ('dept-billing-0001', 'cashier', '收费室', 'dept-billing-0001', 11),
  ('legacy-dept-c081873c0611c93adb587d9373704c69', 'tcm-pharmacy', '中药房', 'legacy-dept-c081873c0611c93adb587d9373704c69', 12);

CREATE TABLE inventory_service_instances (
  id VARCHAR(64) PRIMARY KEY,
  source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
  source_event_id VARCHAR(128) NULL,
  encounter_id VARCHAR(128) NOT NULL,
  care_encounter_id VARCHAR(64) NULL,
  patient_id VARCHAR(64) NOT NULL,
  patient_name VARCHAR(100) NOT NULL,
  visit_no VARCHAR(100) NULL,
  care_type VARCHAR(24) NOT NULL DEFAULT 'outpatient',
  department_id VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  service_code VARCHAR(64) NOT NULL,
  service_name VARCHAR(160) NOT NULL,
  stage_code VARCHAR(64) NULL,
  service_started_at DATETIME(3) NOT NULL,
  business_date DATE NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  source_note VARCHAR(500) NULL,
  created_by VARCHAR(120) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_service_source_event (source_event_id),
  UNIQUE KEY uk_inventory_service_encounter_slot (encounter_id, department_id, service_code, service_started_at),
  INDEX idx_inventory_service_department_date (department_key, business_date, status),
  INDEX idx_inventory_service_patient_date (patient_id, business_date),
  INDEX idx_inventory_service_department (department_id, service_started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_patient_consumption_documents (
  id VARCHAR(64) PRIMARY KEY,
  service_instance_id VARCHAR(64) NOT NULL,
  department_id VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  business_date DATE NOT NULL,
  patient_id VARCHAR(64) NOT NULL,
  encounter_id VARCHAR(128) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  revision INT NOT NULL DEFAULT 0,
  submitted_at DATETIME(3) NULL,
  submitted_by VARCHAR(120) NULL,
  operator_name VARCHAR(120) NOT NULL,
  operator_username VARCHAR(120) NOT NULL,
  raw_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_patient_document_instance (service_instance_id),
  INDEX idx_inventory_patient_document_batch (department_key, business_date, status),
  INDEX idx_inventory_patient_document_patient (patient_id, business_date),
  CONSTRAINT fk_inventory_patient_document_instance FOREIGN KEY (service_instance_id) REFERENCES inventory_service_instances(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_patient_consumption_lines (
  id VARCHAR(64) PRIMARY KEY,
  document_id VARCHAR(64) NOT NULL,
  line_no INT NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  item_name_snapshot VARCHAR(200) NOT NULL,
  unit_snapshot VARCHAR(64) NOT NULL,
  rule_id VARCHAR(96) NULL,
  rule_version VARCHAR(64) NULL,
  preset_quantity DECIMAL(18,6) NULL,
  actual_quantity DECIMAL(14,2) NOT NULL,
  source VARCHAR(24) NOT NULL DEFAULT 'PRESET',
  exception_reason VARCHAR(500) NULL,
  review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  review_note VARCHAR(1000) NULL,
  line_revision INT NOT NULL DEFAULT 0,
  approved_quantity DECIMAL(14,2) NULL,
  consumption_event_id VARCHAR(64) NULL,
  raw_json JSON NOT NULL,
  UNIQUE KEY uk_inventory_patient_line (document_id, line_no),
  INDEX idx_inventory_patient_line_item (item_id),
  INDEX idx_inventory_patient_line_review (review_status),
  CONSTRAINT fk_inventory_patient_line_document FOREIGN KEY (document_id) REFERENCES inventory_patient_consumption_documents(id),
  CONSTRAINT fk_inventory_patient_line_item FOREIGN KEY (item_id) REFERENCES inventory_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_consumption_approval_batches (
  id VARCHAR(64) PRIMARY KEY,
  department_id VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  business_date DATE NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  document_count INT NOT NULL DEFAULT 0,
  line_count INT NOT NULL DEFAULT 0,
  approved_line_count INT NOT NULL DEFAULT 0,
  rejected_line_count INT NOT NULL DEFAULT 0,
  reviewed_by VARCHAR(120) NULL,
  reviewed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_approval_batch (department_id, business_date),
  INDEX idx_inventory_approval_batch_status (business_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_consumption_approval_decisions (
  id VARCHAR(64) PRIMARY KEY,
  batch_id VARCHAR(64) NOT NULL,
  document_id VARCHAR(64) NOT NULL,
  line_id VARCHAR(64) NOT NULL,
  line_revision INT NOT NULL,
  decision VARCHAR(16) NOT NULL,
  decision_note VARCHAR(1000) NULL,
  reviewer_username VARCHAR(120) NOT NULL,
  reviewer_name VARCHAR(120) NOT NULL,
  consumption_event_id VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_approval_decision (line_id, line_revision, decision),
  INDEX idx_inventory_approval_decision_batch (batch_id),
  INDEX idx_inventory_approval_decision_document (document_id),
  CONSTRAINT fk_inventory_approval_decision_batch FOREIGN KEY (batch_id) REFERENCES inventory_consumption_approval_batches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_correction_documents (
  id VARCHAR(64) PRIMARY KEY,
  original_document_id VARCHAR(64) NOT NULL,
  department_id VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  business_date DATE NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  revision INT NOT NULL DEFAULT 0,
  submitted_by VARCHAR(120) NULL,
  reviewed_by VARCHAR(120) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_inventory_correction_original (original_document_id),
  INDEX idx_inventory_correction_batch (department_key, business_date, status),
  CONSTRAINT fk_inventory_correction_original FOREIGN KEY (original_document_id) REFERENCES inventory_patient_consumption_documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_correction_lines (
  id VARCHAR(64) PRIMARY KEY,
  correction_document_id VARCHAR(64) NOT NULL,
  original_line_id VARCHAR(64) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  quantity_delta DECIMAL(14,2) NOT NULL,
  reason VARCHAR(500) NULL,
  review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  consumption_event_id VARCHAR(64) NULL,
  ledger_movement_id VARCHAR(64) NULL,
  raw_json JSON NOT NULL,
  UNIQUE KEY uk_inventory_correction_line (correction_document_id, original_line_id),
  INDEX idx_inventory_correction_line_item (item_id),
  CONSTRAINT fk_inventory_correction_line_document FOREIGN KEY (correction_document_id) REFERENCES inventory_correction_documents(id),
  CONSTRAINT fk_inventory_correction_line_original FOREIGN KEY (original_line_id) REFERENCES inventory_patient_consumption_lines(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_runtime_settings (
  setting_key VARCHAR(64) PRIMARY KEY,
  setting_value VARCHAR(255) NOT NULL DEFAULT '',
  updated_by VARCHAR(120) NOT NULL DEFAULT '',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_runtime_settings (setting_key, setting_value, updated_by)
VALUES ('patient_detail_cutover_date', '', 'system-v56');

ALTER TABLE inventory_quota_rules
  ADD COLUMN item_id VARCHAR(64) NULL AFTER material_name,
  ADD COLUMN trigger_event VARCHAR(64) NULL AFTER measurement_scope,
  ADD COLUMN common_item TINYINT(1) NOT NULL DEFAULT 0 AFTER trigger_event,
  ADD COLUMN patient_triggered TINYINT(1) NOT NULL DEFAULT 1 AFTER common_item,
  ADD INDEX idx_inventory_quota_rule_item (item_id),
  ADD CONSTRAINT fk_inventory_quota_rule_item FOREIGN KEY (item_id) REFERENCES inventory_items(id);

-- The existing mapping workbook already resolved 242 material rows. Reuse those
-- confirmed matches instead of guessing from names or units.
UPDATE inventory_quota_rules r
JOIN inventory_mapping_entries m
  ON m.source_row = r.source_row
 AND (
      m.department = r.department_name
      OR (m.department = '后勤保洁' AND r.department_name = '后勤')
      OR (m.department = '后勤' AND r.department_name = '后勤保洁')
     )
 AND m.matched_item_id IS NOT NULL
SET r.item_id = m.matched_item_id
WHERE r.item_id IS NULL;

UPDATE inventory_quota_rules r
JOIN inventory_mapping_entries m
  ON m.source_row = r.source_row
 AND (
      m.department = r.department_name
      OR (m.department = '后勤保洁' AND r.department_name = '后勤')
      OR (m.department = '后勤' AND r.department_name = '后勤保洁')
     )
SET r.trigger_event = m.trigger_stage
WHERE r.trigger_event IS NULL
  AND m.trigger_stage IS NOT NULL
  AND m.trigger_stage <> '';

ALTER TABLE inventory_consumption_events
  ADD COLUMN source_document_id VARCHAR(64) NULL AFTER command_id,
  ADD COLUMN source_line_id VARCHAR(64) NULL AFTER source_document_id,
  ADD COLUMN correction_document_id VARCHAR(64) NULL AFTER source_line_id,
  ADD COLUMN service_instance_id VARCHAR(64) NULL AFTER correction_document_id,
  ADD COLUMN patient_id VARCHAR(64) NULL AFTER service_instance_id,
  ADD COLUMN idempotency_key VARCHAR(160) NULL AFTER patient_id,
  ADD UNIQUE KEY uk_inventory_consumption_idempotency (idempotency_key),
  ADD INDEX idx_inventory_consumption_source_line (source_line_id),
  ADD INDEX idx_inventory_consumption_patient (patient_id, visit_date);
