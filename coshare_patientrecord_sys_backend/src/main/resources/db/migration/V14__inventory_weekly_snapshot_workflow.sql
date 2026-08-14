-- V14 adds immutable, revisioned weekly planning beside the legacy overwrite table.
-- No legacy inventory_weekly_consumption row is rewritten or deleted by this migration.

CREATE TABLE inventory_weekly_standards (
  id VARCHAR(64) PRIMARY KEY,
  standard_code VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  name VARCHAR(160) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  effective_week VARCHAR(10) NOT NULL,
  expires_week VARCHAR(10),
  hospital_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  policy_json JSON NOT NULL,
  published_by VARCHAR(120),
  published_by_role VARCHAR(64),
  published_at DATETIME(3),
  created_by VARCHAR(120) NOT NULL,
  created_by_role VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_inventory_weekly_standard_version (standard_code, version),
  INDEX idx_inventory_weekly_standard_status_week (status, effective_week, expires_week)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_weekly_standard_lines (
  id VARCHAR(64) PRIMARY KEY,
  standard_id VARCHAR(64) NOT NULL,
  department_id VARCHAR(64) NOT NULL,
  department_name_snapshot VARCHAR(120) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  item_name_snapshot VARCHAR(160) NOT NULL,
  item_unit_snapshot VARCHAR(32),
  expected_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  safety_stock_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  calculation_policy VARCHAR(48) NOT NULL DEFAULT 'EXPECTED_PLUS_SAFETY_MINUS_AVAILABLE',
  line_policy_json JSON NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_inventory_weekly_standard_scope (standard_id, department_id, item_id),
  INDEX idx_inventory_weekly_standard_line_department (department_id, item_id),
  CONSTRAINT fk_inventory_weekly_standard_line_header
    FOREIGN KEY (standard_id) REFERENCES inventory_weekly_standards(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_weekly_snapshots (
  id VARCHAR(64) PRIMARY KEY,
  week_no VARCHAR(10) NOT NULL,
  department_id VARCHAR(64) NOT NULL,
  department_name_snapshot VARCHAR(120) NOT NULL,
  standard_id VARCHAR(64) NOT NULL,
  standard_version INT NOT NULL,
  revision INT NOT NULL,
  previous_snapshot_id VARCHAR(64),
  root_snapshot_id VARCHAR(64),
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  source_cutoff_at DATETIME(3) NOT NULL,
  hospital_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  calculation_version VARCHAR(64) NOT NULL,
  source_digest VARCHAR(64) NOT NULL,
  line_count INT NOT NULL DEFAULT 0,
  total_expected_quantity DECIMAL(16,2) NOT NULL DEFAULT 0,
  total_actual_consumed_quantity DECIMAL(16,2) NOT NULL DEFAULT 0,
  total_adjusted_quantity DECIMAL(16,2) NOT NULL DEFAULT 0,
  revision_reason VARCHAR(1000),
  confirmation_note VARCHAR(1000),
  confirmed_by VARCHAR(120),
  confirmed_by_role VARCHAR(64),
  confirmed_at DATETIME(3),
  created_by VARCHAR(120) NOT NULL,
  created_by_role VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_inventory_weekly_snapshot_revision (week_no, department_id, revision),
  INDEX idx_inventory_weekly_snapshot_current (week_no, department_id, status, revision),
  INDEX idx_inventory_weekly_snapshot_standard (standard_id, standard_version),
  INDEX idx_inventory_weekly_snapshot_previous (previous_snapshot_id),
  INDEX idx_inventory_weekly_snapshot_root (root_snapshot_id),
  CONSTRAINT fk_inventory_weekly_snapshot_standard
    FOREIGN KEY (standard_id) REFERENCES inventory_weekly_standards(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_weekly_snapshot_previous
    FOREIGN KEY (previous_snapshot_id) REFERENCES inventory_weekly_snapshots(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_weekly_snapshot_root
    FOREIGN KEY (root_snapshot_id) REFERENCES inventory_weekly_snapshots(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_weekly_snapshot_lines (
  id VARCHAR(64) PRIMARY KEY,
  snapshot_id VARCHAR(64) NOT NULL,
  standard_line_id VARCHAR(64) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  item_name_snapshot VARCHAR(160) NOT NULL,
  item_unit_snapshot VARCHAR(32),
  opening_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  inbound_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  transfer_in_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  transfer_out_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  consumed_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  reversal_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  returned_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  scrapped_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  count_adjustment_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  closing_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  reserved_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  available_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  expected_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  expected_actual_variance DECIMAL(14,2) NOT NULL DEFAULT 0,
  safety_stock_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  suggested_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  adjusted_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  adjustment_variance DECIMAL(14,2) NOT NULL DEFAULT 0,
  adjustment_reason VARCHAR(1000),
  source_summary_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_inventory_weekly_snapshot_item (snapshot_id, item_id),
  INDEX idx_inventory_weekly_snapshot_line_standard (standard_line_id),
  INDEX idx_inventory_weekly_snapshot_line_item (item_id),
  CONSTRAINT fk_inventory_weekly_snapshot_line_header
    FOREIGN KEY (snapshot_id) REFERENCES inventory_weekly_snapshots(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_weekly_snapshot_line_standard
    FOREIGN KEY (standard_line_id) REFERENCES inventory_weekly_standard_lines(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_weekly_commands (
  id VARCHAR(64) PRIMARY KEY,
  idempotency_key VARCHAR(128) NOT NULL,
  command_type VARCHAR(32) NOT NULL,
  week_no VARCHAR(10) NOT NULL,
  department_id VARCHAR(64) NOT NULL,
  expected_revision INT,
  snapshot_id VARCHAR(64),
  status VARCHAR(24) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  request_json JSON NOT NULL,
  response_json JSON,
  error_code VARCHAR(64),
  error_message VARCHAR(1000),
  requested_by VARCHAR(120) NOT NULL,
  requested_by_role VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3),
  UNIQUE KEY uq_inventory_weekly_command_idempotency (idempotency_key),
  INDEX idx_inventory_weekly_command_scope (week_no, department_id, created_at),
  INDEX idx_inventory_weekly_command_status (status, created_at),
  CONSTRAINT fk_inventory_weekly_command_snapshot
    FOREIGN KEY (snapshot_id) REFERENCES inventory_weekly_snapshots(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_weekly_exports (
  id VARCHAR(64) PRIMARY KEY,
  snapshot_id VARCHAR(64) NOT NULL,
  export_format VARCHAR(16) NOT NULL,
  filter_json JSON NOT NULL,
  row_count INT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(1000),
  content_hash VARCHAR(64) NOT NULL,
  file_size BIGINT NOT NULL,
  requested_by VARCHAR(120) NOT NULL,
  requested_by_role VARCHAR(64) NOT NULL,
  requested_department_id VARCHAR(64),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_inventory_weekly_export_snapshot (snapshot_id, created_at),
  INDEX idx_inventory_weekly_export_operator (requested_by, created_at),
  CONSTRAINT fk_inventory_weekly_export_snapshot
    FOREIGN KEY (snapshot_id) REFERENCES inventory_weekly_snapshots(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_weekly_audit_events (
  id VARCHAR(64) PRIMARY KEY,
  snapshot_id VARCHAR(64),
  command_id VARCHAR(64),
  export_id VARCHAR(64),
  action_code VARCHAR(64) NOT NULL,
  actor_name VARCHAR(120) NOT NULL,
  actor_role VARCHAR(64) NOT NULL,
  department_id VARCHAR(64),
  detail_json JSON NOT NULL,
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_inventory_weekly_audit_snapshot (snapshot_id, occurred_at),
  INDEX idx_inventory_weekly_audit_actor (actor_name, occurred_at),
  CONSTRAINT fk_inventory_weekly_audit_snapshot
    FOREIGN KEY (snapshot_id) REFERENCES inventory_weekly_snapshots(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_weekly_audit_command
    FOREIGN KEY (command_id) REFERENCES inventory_weekly_commands(id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_weekly_audit_export
    FOREIGN KEY (export_id) REFERENCES inventory_weekly_exports(id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
