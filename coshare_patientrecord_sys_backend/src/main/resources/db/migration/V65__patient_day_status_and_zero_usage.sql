-- Patient attendance states, late/correction audit and zero-usage daily reports.

CREATE TABLE inventory_patient_day_statuses (
  id VARCHAR(64) PRIMARY KEY,
  department_key VARCHAR(64) NOT NULL,
  business_date DATE NOT NULL,
  patient_id VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_by VARCHAR(120) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by VARCHAR(120) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_patient_day_status (department_key, business_date, patient_id),
  INDEX idx_patient_day_status_date (business_date, department_key, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_patient_entry_changes (
  id VARCHAR(64) PRIMARY KEY,
  department_key VARCHAR(64) NOT NULL,
  business_date DATE NOT NULL,
  patient_id VARCHAR(64) NOT NULL,
  document_id VARCHAR(64) NULL,
  change_type VARCHAR(24) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  before_json LONGTEXT NULL,
  after_json LONGTEXT NULL,
  operator_username VARCHAR(120) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_patient_entry_change (department_key, business_date, patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE inventory_department_daily_reports
  ADD COLUMN zero_usage TINYINT(1) NOT NULL DEFAULT 0 AFTER status;
