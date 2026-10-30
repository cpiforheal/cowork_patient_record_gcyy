-- Admin review thresholds and frozen weekly/monthly report snapshots.

CREATE TABLE inventory_review_thresholds (
  id VARCHAR(64) PRIMARY KEY,
  scope VARCHAR(24) NOT NULL,
  department_key VARCHAR(64) NOT NULL DEFAULT '',
  item_id VARCHAR(64) NOT NULL DEFAULT '',
  threshold_key VARCHAR(64) NOT NULL,
  threshold_value DECIMAL(18,6) NOT NULL,
  updated_by VARCHAR(120) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_review_threshold (scope, department_key, item_id, threshold_key),
  INDEX idx_inventory_review_threshold_key (threshold_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_review_thresholds
  (id, scope, department_key, item_id, threshold_key, threshold_value, updated_by)
VALUES
  ('review-threshold-global-no-consumption-ratio', 'GLOBAL', '', '', 'NO_CONSUMPTION_RATIO', 0.500000, 'system-v64'),
  ('review-threshold-global-no-consumption-min-docs', 'GLOBAL', '', '', 'NO_CONSUMPTION_MIN_DOCS', 5.000000, 'system-v64'),
  ('review-threshold-global-trend-wow-ratio', 'GLOBAL', '', '', 'TREND_WOW_RATIO', 0.300000, 'system-v64'),
  ('review-threshold-global-trend-min-delta', 'GLOBAL', '', '', 'TREND_MIN_DELTA', 5.000000, 'system-v64'),
  ('review-threshold-global-cross-dept-multiple', 'GLOBAL', '', '', 'CROSS_DEPT_MULTIPLE', 2.000000, 'system-v64'),
  ('review-threshold-global-cross-dept-min-base', 'GLOBAL', '', '', 'CROSS_DEPT_MIN_BASE', 5.000000, 'system-v64'),
  ('review-threshold-global-preset-deviation-ratio', 'GLOBAL', '', '', 'PRESET_DEVIATION_RATIO', 0.300000, 'system-v64'),
  ('review-threshold-global-preset-deviation-min-docs', 'GLOBAL', '', '', 'PRESET_DEVIATION_MIN_DOCS', 5.000000, 'system-v64'),
  ('review-threshold-global-late-hour', 'GLOBAL', '', '', 'LATE_HOUR', 0.000000, 'system-v64'),
  ('review-threshold-global-centralized-window-minutes', 'GLOBAL', '', '', 'CENTRALIZED_WINDOW_MINUTES', 30.000000, 'system-v64'),
  ('review-threshold-global-centralized-min-docs', 'GLOBAL', '', '', 'CENTRALIZED_MIN_DOCS', 5.000000, 'system-v64');

CREATE TABLE inventory_admin_report_snapshots (
  id VARCHAR(64) PRIMARY KEY,
  report_type VARCHAR(16) NOT NULL,
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'READY',
  payload_json LONGTEXT NOT NULL,
  generated_by VARCHAR(120) NOT NULL,
  generated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  data_revision VARCHAR(64) NOT NULL DEFAULT '',
  INDEX idx_inventory_admin_report_type (report_type, period_start, period_end),
  INDEX idx_inventory_admin_report_generated (generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
