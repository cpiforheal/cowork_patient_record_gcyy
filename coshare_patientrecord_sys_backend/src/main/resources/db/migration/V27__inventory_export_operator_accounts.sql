ALTER TABLE inventory_department_daily_drafts
  ADD COLUMN operator_username VARCHAR(120) NULL AFTER operator_name;

ALTER TABLE inventory_patient_consumption_drafts
  ADD COLUMN operator_username VARCHAR(120) NULL AFTER operator_name;
