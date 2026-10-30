-- Clinical-stage to inventory-department mapping used to enroll patients into
-- the department consumable workbench. Only explicit mappings are enrolled.

CREATE TABLE inventory_service_event_mappings (
  id VARCHAR(64) PRIMARY KEY,
  event_source VARCHAR(32) NOT NULL,
  event_code VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  service_code VARCHAR(64) NOT NULL,
  service_name VARCHAR(160) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_event_mapping (event_source, event_code),
  INDEX idx_inventory_event_mapping_enabled (enabled, department_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_service_event_mappings
  (id, event_source, event_code, department_key, service_code, service_name, enabled)
VALUES
  ('inv-map-inspection', 'CLINICAL_STAGE', 'INSPECTION', 'inspection', 'INSPECTION', '检查', 1),
  ('inv-map-tcm', 'CLINICAL_STAGE', 'TCM', 'tcm', 'TCM', '中医诊疗', 1),
  ('inv-map-surgery', 'CLINICAL_STAGE', 'SURGERY', 'operating', 'SURGERY', '手术', 1),
  ('inv-map-nursing', 'CLINICAL_STAGE', 'NURSING', 'nursing', 'NURSING', '护理', 1);
