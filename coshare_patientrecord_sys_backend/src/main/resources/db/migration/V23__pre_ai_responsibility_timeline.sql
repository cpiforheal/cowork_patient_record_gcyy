-- Additive only: preserve all existing patient, encounter, inventory, and audit data.
ALTER TABLE pre_ai_encounters
  ADD COLUMN care_situation_tags VARCHAR(64) NULL AFTER inventory_care_type,
  ADD INDEX idx_pre_ai_encounter_care_tags (care_situation_tags);

ALTER TABLE pre_ai_audit_logs
  ADD COLUMN operator_id VARCHAR(64) NULL AFTER operator_role,
  ADD COLUMN operator_username VARCHAR(100) NULL AFTER operator_id,
  ADD COLUMN operator_department VARCHAR(128) NULL AFTER operator_username,
  ADD INDEX idx_pre_ai_audit_timeline (encounter_id, created_at, id);
