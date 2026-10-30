-- Batch entry support: distinguish "explicitly no consumables" from "not started"
-- and speed up per-user recent-item queries.

ALTER TABLE inventory_patient_consumption_documents
  ADD COLUMN no_consumption TINYINT(1) NOT NULL DEFAULT 0 AFTER submitted_by,
  ADD INDEX idx_inventory_patient_document_operator (operator_username, department_key, business_date);
