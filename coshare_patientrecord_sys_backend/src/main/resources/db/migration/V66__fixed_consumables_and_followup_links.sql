-- Fixed daily consumables and follow-up appointment linkage.

ALTER TABLE clinic_recheck_schedule
  ADD COLUMN time_slot VARCHAR(16) NULL AFTER plan_date;

ALTER TABLE inventory_service_instances
  ADD COLUMN source_recheck_id VARCHAR(64) NULL AFTER source_event_id;

CREATE INDEX idx_inventory_instance_recheck
  ON inventory_service_instances (source_recheck_id);
