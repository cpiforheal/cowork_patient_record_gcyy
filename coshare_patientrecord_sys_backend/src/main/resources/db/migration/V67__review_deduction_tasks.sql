ALTER TABLE inventory_exception_tasks
  ADD COLUMN source_type VARCHAR(32) NULL AFTER encounter_id,
  ADD COLUMN source_id VARCHAR(64) NULL AFTER source_type,
  ADD COLUMN line_id VARCHAR(64) NULL AFTER source_id,
  ADD UNIQUE KEY uk_inventory_exception_tasks_line (line_id, exception_type);
