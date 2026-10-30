-- One patient can only be entered once per department per business day.
-- Keep the instance with the newest document, remove older duplicate drafts,
-- then enforce the rule at the database level.

CREATE TEMPORARY TABLE inventory_instance_rank AS
SELECT si.id instance_id,
       si.patient_id,
       si.department_key,
       si.business_date,
       d.id document_id,
       ROW_NUMBER() OVER (
         PARTITION BY si.patient_id, si.department_key, si.business_date
         ORDER BY (d.id IS NULL) ASC, COALESCE(d.updated_at, si.updated_at) DESC,
                  COALESCE(d.revision, 0) DESC, si.service_started_at DESC, si.id DESC
       ) rn
FROM inventory_service_instances si
LEFT JOIN inventory_patient_consumption_documents d ON d.service_instance_id = si.id;

CREATE TEMPORARY TABLE inventory_instance_remove AS
SELECT r.instance_id, r.document_id
FROM inventory_instance_rank r
LEFT JOIN inventory_patient_consumption_documents d ON d.id = r.document_id
WHERE r.rn > 1 AND (r.document_id IS NULL OR d.status = 'DRAFT');

DELETE l FROM inventory_patient_consumption_lines l
JOIN inventory_instance_remove r ON r.document_id = l.document_id;

DELETE FROM inventory_patient_consumption_documents
WHERE id IN (SELECT document_id FROM inventory_instance_remove WHERE document_id IS NOT NULL);

DELETE FROM inventory_service_instances
WHERE id IN (SELECT instance_id FROM inventory_instance_remove);

DROP TEMPORARY TABLE inventory_instance_remove;
DROP TEMPORARY TABLE inventory_instance_rank;

ALTER TABLE inventory_service_instances
  ADD UNIQUE KEY uk_inventory_service_patient_day (patient_id, department_key, business_date);
