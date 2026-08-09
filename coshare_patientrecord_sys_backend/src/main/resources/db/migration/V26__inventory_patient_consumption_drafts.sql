CREATE TABLE inventory_patient_consumption_drafts (
  id VARCHAR(64) PRIMARY KEY,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  patient_id VARCHAR(64) NOT NULL,
  encounter_id VARCHAR(128) NOT NULL,
  patient_name VARCHAR(100) NOT NULL,
  visit_no VARCHAR(100) NULL,
  business_date DATE NOT NULL,
  service_at VARCHAR(32) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  revision INT NOT NULL DEFAULT 1,
  operator_name VARCHAR(120) NOT NULL,
  raw_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_inventory_patient_draft_department_date (department_key, business_date),
  INDEX idx_inventory_patient_draft_patient_date (patient_id, business_date),
  INDEX idx_inventory_patient_draft_encounter (encounter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_patient_consumption_draft_lines (
  id VARCHAR(64) PRIMARY KEY,
  draft_id VARCHAR(64) NOT NULL,
  line_no INT NOT NULL,
  service_item_id VARCHAR(128) NOT NULL,
  service_item_name VARCHAR(160) NOT NULL,
  material_name VARCHAR(200) NOT NULL,
  unit VARCHAR(64) NULL,
  standard_quantity DECIMAL(18, 6) NULL,
  actual_quantity DECIMAL(18, 6) NOT NULL,
  exception_reason VARCHAR(255) NULL,
  raw_json JSON NOT NULL,
  UNIQUE KEY uk_inventory_patient_draft_line (draft_id, line_no),
  INDEX idx_inventory_patient_draft_line_material (material_name, unit),
  CONSTRAINT fk_inventory_patient_draft_line_draft FOREIGN KEY (draft_id) REFERENCES inventory_patient_consumption_drafts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
