-- Excel 定额同步：科室级模式、精确分数、独立规则行
ALTER TABLE inventory_departments
  ADD COLUMN quota_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY' AFTER entry_mode,
  ADD COLUMN quota_mode_effective_date DATE NULL AFTER quota_mode,
  ADD COLUMN quota_mode_enabled_by VARCHAR(120) NULL AFTER quota_mode_effective_date,
  ADD COLUMN quota_mode_enabled_at DATETIME(3) NULL AFTER quota_mode_enabled_by;

ALTER TABLE inventory_quota_rules
  ADD COLUMN source_row_key VARCHAR(160) NULL AFTER source_row,
  ADD COLUMN source_numerator DECIMAL(18,6) NULL AFTER standard_quantity,
  ADD COLUMN source_denominator DECIMAL(18,6) NULL AFTER source_numerator,
  ADD COLUMN period_days INT NOT NULL DEFAULT 1 AFTER source_denominator,
  ADD COLUMN source_unit VARCHAR(32) NULL AFTER period_days,
  ADD COLUMN source_text VARCHAR(120) NULL AFTER source_unit,
  ADD COLUMN included_in_quota TINYINT(1) NOT NULL DEFAULT 1 AFTER source_text,
  ADD COLUMN source_note VARCHAR(500) NULL AFTER included_in_quota,
  ADD UNIQUE KEY uk_inventory_quota_rule_source_row (source_row_key);

ALTER TABLE inventory_department_daily_report_lines
  ADD COLUMN quota_rule_id VARCHAR(64) NULL AFTER item_id,
  ADD COLUMN source_row_key VARCHAR(160) NULL AFTER quota_rule_id,
  ADD INDEX idx_inventory_daily_report_line_rule (quota_rule_id);

ALTER TABLE inventory_quota_exceptions
  ADD COLUMN quota_rule_id VARCHAR(64) NULL AFTER item_id,
  ADD COLUMN source_row_key VARCHAR(160) NULL AFTER quota_rule_id,
  ADD INDEX idx_inventory_quota_exception_rule (quota_rule_id);
