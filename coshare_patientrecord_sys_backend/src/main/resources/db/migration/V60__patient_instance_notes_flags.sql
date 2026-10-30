-- Patient-level workbench notes and attention markers, plus a source-directory
-- index for department staff to add patients from the clinical archive.

ALTER TABLE inventory_service_instances
  ADD COLUMN special_note VARCHAR(1000) NOT NULL DEFAULT '' AFTER source_note,
  ADD COLUMN attention_flag TINYINT(1) NOT NULL DEFAULT 0 AFTER special_note,
  ADD INDEX idx_inventory_service_attention (department_key, business_date, attention_flag);
