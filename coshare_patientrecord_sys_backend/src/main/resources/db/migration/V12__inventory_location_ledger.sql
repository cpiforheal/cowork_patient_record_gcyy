CREATE TABLE inventory_locations (
  id VARCHAR(100) PRIMARY KEY,
  location_type VARCHAR(32) NOT NULL,
  department_id VARCHAR(64),
  department_name_snapshot VARCHAR(120),
  name VARCHAR(160) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  opening_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  opening_confirmed_by VARCHAR(120),
  opening_confirmed_at DATETIME(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_location_department (location_type, department_id),
  INDEX idx_inventory_location_type_status (location_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_locations (
  id, location_type, department_id, department_name_snapshot, name, status, opening_confirmed
) VALUES
  ('loc-central', 'CENTRAL', NULL, NULL, '中央仓库', 'ACTIVE', TRUE),
  ('loc-in-transit', 'IN_TRANSIT', NULL, NULL, '配送在途', 'ACTIVE', TRUE);

INSERT INTO inventory_locations (
  id, location_type, department_id, department_name_snapshot, name, status, opening_confirmed
)
SELECT
  CONCAT('loc-dept-', LEFT(SHA2(d.id, 256), 32)),
  'DEPARTMENT',
  d.id,
  d.name,
  CONCAT(d.name, '科室库'),
  CASE WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(d.raw_json, '$.status')), 'active') IN ('disabled', 'inactive')
       THEN 'INACTIVE' ELSE 'ACTIVE' END,
  FALSE
FROM clinic_departments d;

CREATE TABLE inventory_batch_balances (
  id VARCHAR(64) PRIMARY KEY,
  location_id VARCHAR(100) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  batch_id VARCHAR(64) NOT NULL,
  quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  reserved_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_balance_location_batch (location_id, batch_id),
  INDEX idx_inventory_balance_item_location (item_id, location_id),
  INDEX idx_inventory_balance_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_batch_balances (
  id, location_id, item_id, batch_id, quantity, reserved_quantity, version
)
SELECT
  CONCAT('bal-', LEFT(SHA2(CONCAT('CENTRAL|', b.id), 256), 32)),
  'loc-central', b.item_id, b.id, GREATEST(b.quantity, 0), 0, 0
FROM inventory_batches b;

CREATE TABLE inventory_transfers (
  id VARCHAR(64) PRIMARY KEY,
  request_id VARCHAR(64),
  transfer_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  from_location_id VARCHAR(100) NOT NULL,
  to_location_id VARCHAR(100) NOT NULL,
  department_id VARCHAR(64),
  department_name_snapshot VARCHAR(120),
  client_request_id VARCHAR(100),
  reason VARCHAR(500),
  created_by VARCHAR(120),
  approved_by VARCHAR(120),
  issued_by VARCHAR(120),
  received_by VARCHAR(120),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  approved_at DATETIME(3),
  issued_at DATETIME(3),
  received_at DATETIME(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_transfer_request_type (request_id, transfer_type),
  UNIQUE KEY uk_inventory_transfer_client_request (client_request_id),
  INDEX idx_inventory_transfer_department_status (department_id, status),
  INDEX idx_inventory_transfer_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_transfer_lines (
  id VARCHAR(64) PRIMARY KEY,
  transfer_id VARCHAR(64) NOT NULL,
  request_line_id VARCHAR(64),
  item_id VARCHAR(64) NOT NULL,
  batch_id VARCHAR(64) NOT NULL,
  requested_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  reserved_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  moved_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  received_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_transfer_line_allocation (transfer_id, request_line_id, batch_id),
  INDEX idx_inventory_transfer_line_transfer (transfer_id),
  INDEX idx_inventory_transfer_line_item_batch (item_id, batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_ledger_movements (
  id VARCHAR(64) PRIMARY KEY,
  item_id VARCHAR(64) NOT NULL,
  batch_id VARCHAR(64),
  from_location_id VARCHAR(100),
  to_location_id VARCHAR(100),
  movement_type VARCHAR(48) NOT NULL,
  quantity DECIMAL(14,2) NOT NULL,
  department_id VARCHAR(64),
  department_name_snapshot VARCHAR(120),
  related_type VARCHAR(48),
  related_id VARCHAR(100),
  consumption_event_id VARCHAR(64),
  reversal_of_movement_id VARCHAR(64),
  operator_name VARCHAR(120),
  reason VARCHAR(500),
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  raw_json JSON NOT NULL,
  INDEX idx_inventory_ledger_department_date (department_id, occurred_at),
  INDEX idx_inventory_ledger_item_date (item_id, occurred_at),
  INDEX idx_inventory_ledger_related (related_type, related_id),
  INDEX idx_inventory_ledger_consumption (consumption_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_ledger_movements (
  id, item_id, batch_id, from_location_id, to_location_id, movement_type, quantity,
  related_type, related_id, operator_name, reason, occurred_at, raw_json
)
SELECT
  CONCAT('opening-', LEFT(SHA2(CONCAT('CENTRAL|', b.id), 256), 32)),
  b.item_id, b.id, NULL, 'loc-central', 'OPENING_BALANCE', GREATEST(b.quantity, 0),
  'MIGRATION', 'V12', 'system', 'V12 中央仓库期初', CURRENT_TIMESTAMP(3),
  JSON_OBJECT('source', 'inventory_batches.quantity', 'migration', 'V12')
FROM inventory_batches b
WHERE b.quantity > 0;

CREATE TABLE inventory_opening_suggestions (
  id VARCHAR(64) PRIMARY KEY,
  department_id VARCHAR(64) NOT NULL,
  department_name_snapshot VARCHAR(120) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  batch_id VARCHAR(64) NOT NULL,
  suggested_quantity DECIMAL(14,2) NOT NULL DEFAULT 0,
  confirmed_quantity DECIMAL(14,2),
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  source_summary VARCHAR(500),
  confirmed_by VARCHAR(120),
  confirmed_at DATETIME(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_opening_suggestion (department_id, batch_id),
  INDEX idx_inventory_opening_department_status (department_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_opening_suggestions (
  id, department_id, department_name_snapshot, item_id, batch_id, suggested_quantity, status, source_summary
)
SELECT
  CONCAT('suggest-', LEFT(SHA2(CONCAT(d.id, '|', m.batch_id), 256), 32)),
  d.id, d.name, m.item_id, m.batch_id,
  GREATEST(SUM(CASE
    WHEN m.movement_type = 'issue' THEN -m.quantity
    WHEN m.movement_type = 'auto_consume' THEN m.quantity
    WHEN m.movement_type = 'return' THEN -m.quantity
    WHEN m.movement_type = 'scrap' THEN m.quantity
    WHEN m.movement_type = 'count' THEN m.quantity
    ELSE 0 END), 0),
  'PENDING',
  '由V8历史发放/消耗/退库/报损流水推算，需盘点确认'
FROM inventory_movements m
JOIN clinic_departments d ON d.name = m.department
WHERE COALESCE(m.department, '') <> '' AND COALESCE(m.batch_id, '') <> ''
GROUP BY d.id, d.name, m.item_id, m.batch_id
HAVING GREATEST(SUM(CASE
    WHEN m.movement_type = 'issue' THEN -m.quantity
    WHEN m.movement_type = 'auto_consume' THEN m.quantity
    WHEN m.movement_type = 'return' THEN -m.quantity
    WHEN m.movement_type = 'scrap' THEN m.quantity
    WHEN m.movement_type = 'count' THEN m.quantity
    ELSE 0 END), 0) > 0;

CREATE TABLE inventory_stage_consumption_commands (
  id VARCHAR(64) PRIMARY KEY,
  encounter_id VARCHAR(120) NOT NULL,
  trigger_stage VARCHAR(32) NOT NULL,
  completion_version BIGINT NOT NULL,
  command_type VARCHAR(24) NOT NULL DEFAULT 'CONSUME',
  department_id VARCHAR(64) NOT NULL,
  department_name_snapshot VARCHAR(120),
  case_token VARCHAR(160),
  route VARCHAR(32),
  visit_date DATE,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3),
  reversal_of_command_id VARCHAR(64),
  requested_by VARCHAR(120),
  reason VARCHAR(500),
  error_code VARCHAR(64),
  error_message VARCHAR(500),
  payload_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at DATETIME(3),
  completed_at DATETIME(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_stage_command (encounter_id, trigger_stage, completion_version, command_type),
  INDEX idx_inventory_stage_command_poll (status, next_attempt_at, created_at),
  INDEX idx_inventory_stage_command_encounter (encounter_id, trigger_stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_exception_tasks (
  id VARCHAR(64) PRIMARY KEY,
  command_id VARCHAR(64),
  exception_type VARCHAR(48) NOT NULL,
  severity VARCHAR(16) NOT NULL DEFAULT 'HIGH',
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  department_id VARCHAR(64),
  department_name_snapshot VARCHAR(120),
  encounter_id VARCHAR(120),
  trigger_stage VARCHAR(32),
  item_id VARCHAR(64),
  message VARCHAR(500) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  resolved_by VARCHAR(120),
  resolution_note VARCHAR(500),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at DATETIME(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_exception_open_command (command_id, exception_type),
  INDEX idx_inventory_exception_status_department (status, department_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE inventory_packages
  ADD COLUMN department_id VARCHAR(64) NULL AFTER department,
  ADD COLUMN trigger_stage VARCHAR(32) NOT NULL DEFAULT 'REVIEW' AFTER care_type,
  ADD INDEX idx_inventory_packages_department_stage (department_id, care_type, trigger_stage, status);

UPDATE inventory_packages p
JOIN clinic_departments d ON d.name = p.department
SET p.department_id = d.id,
    p.raw_json = JSON_SET(p.raw_json, '$.departmentId', d.id, '$.triggerStage', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.raw_json, '$.triggerStage')), 'REVIEW'));

ALTER TABLE inventory_consumption_events
  DROP INDEX uk_inventory_consumption_encounter_route,
  ADD COLUMN command_id VARCHAR(64) NULL AFTER id,
  ADD COLUMN department_id VARCHAR(64) NULL AFTER department,
  ADD COLUMN trigger_stage VARCHAR(32) NULL AFTER route,
  ADD COLUMN completion_version BIGINT NULL AFTER trigger_stage,
  ADD COLUMN event_kind VARCHAR(24) NOT NULL DEFAULT 'CONSUMPTION' AFTER status,
  ADD COLUMN reversal_of_event_id VARCHAR(64) NULL AFTER event_kind,
  ADD UNIQUE KEY uk_inventory_consumption_command (command_id),
  ADD INDEX idx_inventory_consumption_department_stage (department_id, trigger_stage, visit_date);

ALTER TABLE inventory_consumption_details
  ADD COLUMN location_id VARCHAR(100) NULL AFTER batch_id,
  ADD COLUMN detail_kind VARCHAR(24) NOT NULL DEFAULT 'CONSUMPTION' AFTER quantity;

ALTER TABLE inventory_items
  ADD COLUMN safety_stock DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER low_stock_threshold;

ALTER TABLE inventory_weekly_consumption
  ADD COLUMN actual_consumed_quantity DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER consumed_quantity,
  ADD COLUMN suggested_quantity DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER next_week_quantity,
  ADD COLUMN adjusted_quantity DECIMAL(14,2) NULL AFTER suggested_quantity,
  ADD COLUMN safety_stock DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER adjusted_quantity,
  ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'LEDGER' AFTER safety_stock;
