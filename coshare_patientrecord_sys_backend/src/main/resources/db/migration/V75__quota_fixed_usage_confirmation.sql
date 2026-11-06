-- Fixed-usage confirmation pilot for fixed-only departments (TCM pharmacy first).
ALTER TABLE inventory_items
  ADD COLUMN count_unit VARCHAR(32) NULL AFTER unit,
  ADD COLUMN count_per_unit DECIMAL(18,6) NULL AFTER count_unit;

UPDATE inventory_items
SET count_unit = '个', count_per_unit = 100
WHERE name = '小白袋子' AND unit = '把';

ALTER TABLE inventory_department_daily_report_lines
  ADD COLUMN usage_status VARCHAR(24) NOT NULL DEFAULT 'AS_QUOTA' AFTER exception_status,
  ADD COLUMN reported_quantity DECIMAL(18,6) NULL AFTER usage_status,
  ADD COLUMN reported_unit VARCHAR(32) NULL AFTER reported_quantity,
  ADD COLUMN confirmed_quantity DECIMAL(18,6) NULL AFTER reported_unit,
  ADD COLUMN usage_confirmed_by VARCHAR(100) NULL AFTER confirmed_quantity,
  ADD COLUMN usage_confirmed_at VARCHAR(32) NULL AFTER usage_confirmed_by;
