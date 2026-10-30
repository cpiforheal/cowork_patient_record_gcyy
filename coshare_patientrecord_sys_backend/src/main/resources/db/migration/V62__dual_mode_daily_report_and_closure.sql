-- Dual-mode inventory workflow: clinical departments use patient-bound entry,
-- non-patient departments use a department daily report.

ALTER TABLE inventory_departments
  ADD COLUMN entry_mode VARCHAR(24) NOT NULL DEFAULT 'PATIENT' AFTER clinic_department_id;

UPDATE inventory_departments
SET entry_mode = 'DAILY'
WHERE department_key IN ('logistics', 'cashier', 'western-pharmacy', 'tcm-pharmacy');

CREATE TABLE inventory_department_daily_reports (
  id VARCHAR(64) PRIMARY KEY,
  department_id VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  business_date DATE NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  revision INT NOT NULL DEFAULT 0,
  note VARCHAR(1000) NOT NULL DEFAULT '',
  submitted_by VARCHAR(120) NULL,
  submitted_at DATETIME(3) NULL,
  reviewed_by VARCHAR(120) NULL,
  reviewed_at DATETIME(3) NULL,
  raw_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_daily_report_day (department_id, business_date),
  INDEX idx_inventory_daily_report_department_date (department_key, business_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_department_daily_report_lines (
  id VARCHAR(64) PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL,
  line_no INT NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  item_name_snapshot VARCHAR(200) NOT NULL,
  unit_snapshot VARCHAR(64) NOT NULL,
  preset_quantity DECIMAL(18,6) NULL,
  actual_quantity DECIMAL(14,2) NOT NULL,
  source VARCHAR(24) NOT NULL DEFAULT 'TEMPLATE',
  exception_reason VARCHAR(500) NULL,
  review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  review_note VARCHAR(1000) NULL,
  consumption_event_id VARCHAR(64) NULL,
  raw_json JSON NOT NULL,
  UNIQUE KEY uk_inventory_daily_report_line (report_id, line_no),
  INDEX idx_inventory_daily_report_line_item (item_id),
  CONSTRAINT fk_inventory_daily_report_line_report FOREIGN KEY (report_id) REFERENCES inventory_department_daily_reports(id),
  CONSTRAINT fk_inventory_daily_report_line_item FOREIGN KEY (item_id) REFERENCES inventory_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_department_day_closures (
  id VARCHAR(64) PRIMARY KEY,
  department_id VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  business_date DATE NOT NULL,
  entry_mode VARCHAR(24) NOT NULL DEFAULT 'PATIENT',
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  submitted_by VARCHAR(120) NULL,
  submitted_at DATETIME(3) NULL,
  locked_at DATETIME(3) NULL,
  note VARCHAR(1000) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_day_closure (department_id, business_date),
  INDEX idx_inventory_day_closure_date (business_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
