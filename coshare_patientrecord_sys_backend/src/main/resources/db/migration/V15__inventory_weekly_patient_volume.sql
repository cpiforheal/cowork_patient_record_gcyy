-- V15 separates outpatient/inpatient weekly standards for the same item and
-- keeps weekly snapshot rows auditable by care type.

ALTER TABLE inventory_weekly_standard_lines
  ADD COLUMN care_type VARCHAR(32) NOT NULL DEFAULT 'outpatient' AFTER department_name_snapshot;

UPDATE inventory_weekly_standard_lines
SET care_type = COALESCE(
  NULLIF(JSON_UNQUOTE(JSON_EXTRACT(line_policy_json, '$.careType')), ''),
  'outpatient'
);

ALTER TABLE inventory_weekly_standard_lines
  DROP INDEX uq_inventory_weekly_standard_scope,
  ADD UNIQUE KEY uq_inventory_weekly_standard_scope (standard_id, department_id, item_id, care_type),
  ADD INDEX idx_inventory_weekly_standard_line_care (department_id, care_type, item_id);

ALTER TABLE inventory_weekly_snapshot_lines
  ADD COLUMN care_type VARCHAR(32) NOT NULL DEFAULT 'outpatient' AFTER standard_line_id;

UPDATE inventory_weekly_snapshot_lines l
LEFT JOIN inventory_weekly_standard_lines s ON s.id = l.standard_line_id
SET l.care_type = COALESCE(
  s.care_type,
  NULLIF(JSON_UNQUOTE(JSON_EXTRACT(l.source_summary_json, '$.careType')), ''),
  'outpatient'
);

ALTER TABLE inventory_weekly_snapshot_lines
  DROP INDEX uq_inventory_weekly_snapshot_item,
  ADD UNIQUE KEY uq_inventory_weekly_snapshot_item (snapshot_id, item_id, care_type),
  ADD INDEX idx_inventory_weekly_snapshot_line_care (snapshot_id, care_type, item_id);
