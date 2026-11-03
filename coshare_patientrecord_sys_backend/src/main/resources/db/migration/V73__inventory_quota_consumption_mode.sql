-- 定额消耗模式：院定每人次定额 × 接诊人次
-- 默认保持 LEGACY，管理员补齐定额后再按生效日切换到 QUOTA。

INSERT INTO inventory_runtime_settings (setting_key, setting_value, updated_by)
VALUES ('inventory_quota_mode', 'LEGACY', 'system-v73'),
       ('inventory_quota_mode_effective_date', '', 'system-v73'),
       ('inventory_quota_mode_confirmed_by', '', 'system-v73'),
       ('inventory_quota_mode_changed_at', '', 'system-v73');

ALTER TABLE inventory_quota_rules
  ADD COLUMN tolerance_mode VARCHAR(16) NOT NULL DEFAULT 'NONE' AFTER standard_quantity,
  ADD COLUMN tolerance_value DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER tolerance_mode;

ALTER TABLE inventory_department_daily_reports
  ADD COLUMN report_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY' AFTER business_date,
  ADD COLUMN quota_version_id VARCHAR(64) NULL AFTER report_mode,
  ADD COLUMN headcount_outpatient INT NOT NULL DEFAULT 0 AFTER quota_version_id,
  ADD COLUMN headcount_inpatient INT NOT NULL DEFAULT 0 AFTER headcount_outpatient,
  ADD COLUMN headcount_manual INT NOT NULL DEFAULT 0 AFTER headcount_inpatient,
  ADD COLUMN headcount_source VARCHAR(16) NOT NULL DEFAULT '' AFTER headcount_manual,
  ADD COLUMN headcount_reason VARCHAR(500) NULL AFTER headcount_source,
  ADD COLUMN quota_computed_total DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER headcount_reason,
  ADD COLUMN quota_deducted_total DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER quota_computed_total,
  ADD COLUMN exception_total DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER quota_deducted_total;

ALTER TABLE inventory_department_daily_report_lines
  MODIFY COLUMN actual_quantity DECIMAL(18,6) NOT NULL,
  ADD COLUMN quota_quantity DECIMAL(18,6) NULL AFTER preset_quantity,
  ADD COLUMN quota_deducted_quantity DECIMAL(18,6) NULL AFTER quota_quantity,
  ADD COLUMN extra_quantity DECIMAL(18,6) NULL AFTER quota_deducted_quantity,
  ADD COLUMN tolerance_mode VARCHAR(16) NOT NULL DEFAULT 'NONE' AFTER extra_quantity,
  ADD COLUMN tolerance_value DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER tolerance_mode,
  ADD COLUMN tolerance_quantity DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER tolerance_value,
  ADD COLUMN exception_status VARCHAR(24) NOT NULL DEFAULT 'NONE' AFTER tolerance_quantity,
  ADD COLUMN exception_approved_quantity DECIMAL(18,6) NOT NULL DEFAULT 0 AFTER exception_status,
  ADD COLUMN quota_event_id VARCHAR(64) NULL AFTER exception_approved_quantity,
  ADD COLUMN exception_event_id VARCHAR(64) NULL AFTER quota_event_id,
  ADD COLUMN linked_patient_id VARCHAR(64) NULL AFTER exception_event_id,
  ADD COLUMN linked_encounter_id VARCHAR(64) NULL AFTER linked_patient_id,
  ADD COLUMN headcount_basis VARCHAR(64) NULL AFTER linked_encounter_id,
  ADD INDEX idx_inventory_daily_report_line_quota (report_id, exception_status);

CREATE TABLE inventory_quota_exceptions (
  id VARCHAR(64) PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL,
  line_id VARCHAR(64) NULL,
  department_id VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  business_date DATE NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  item_name_snapshot VARCHAR(200) NOT NULL DEFAULT '',
  unit_snapshot VARCHAR(64) NOT NULL DEFAULT '',
  quota_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  actual_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  extra_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  tolerance_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  approved_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  reason VARCHAR(500) NOT NULL DEFAULT '',
  review_note VARCHAR(1000) NULL,
  reviewed_by VARCHAR(120) NULL,
  reviewed_at DATETIME(3) NULL,
  revision INT NOT NULL DEFAULT 1,
  created_by VARCHAR(120) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_quota_exception_line_rev (report_id, item_id, revision),
  INDEX idx_inventory_quota_exception_queue (status, business_date, department_key),
  INDEX idx_inventory_quota_exception_report (report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_quota_exception_photos (
  id VARCHAR(64) PRIMARY KEY,
  exception_id VARCHAR(64) NOT NULL,
  storage_path VARCHAR(500) NOT NULL,
  content_type VARCHAR(120) NOT NULL DEFAULT '',
  size_bytes BIGINT NOT NULL DEFAULT 0,
  original_name VARCHAR(255) NOT NULL DEFAULT '',
  uploaded_by VARCHAR(120) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_inventory_quota_exception_photo (exception_id),
  CONSTRAINT fk_inventory_quota_exception_photo_exception
    FOREIGN KEY (exception_id) REFERENCES inventory_quota_exceptions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_quota_headcount_corrections (
  id VARCHAR(64) PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL,
  department_id VARCHAR(64) NOT NULL,
  department_key VARCHAR(64) NOT NULL,
  business_date DATE NOT NULL,
  before_json JSON NOT NULL,
  after_json JSON NOT NULL,
  reason VARCHAR(500) NOT NULL DEFAULT '',
  corrected_by VARCHAR(120) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_inventory_quota_headcount_report (report_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE inventory_department_daily_drafts
  ADD COLUMN frozen TINYINT(1) NOT NULL DEFAULT 0 AFTER revision,
  ADD INDEX idx_inventory_daily_draft_frozen (business_date, frozen);
