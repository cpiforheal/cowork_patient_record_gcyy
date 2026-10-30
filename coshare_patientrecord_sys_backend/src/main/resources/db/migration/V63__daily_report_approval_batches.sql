-- Department daily reports share the warehouse review queue with patient documents.
-- The batch type keeps the two document models distinguishable.

ALTER TABLE inventory_consumption_approval_batches
  ADD COLUMN batch_type VARCHAR(24) NOT NULL DEFAULT 'PATIENT' AFTER department_name;

CREATE INDEX idx_inventory_approval_batch_type
  ON inventory_consumption_approval_batches (batch_type, business_date, status);

-- Existing submitted daily reports should appear in the warehouse review queue.
INSERT INTO inventory_consumption_approval_batches
  (id, department_id, department_key, department_name, batch_type, business_date, status,
   document_count, line_count, approved_line_count, rejected_line_count)
SELECT CONCAT('daily-approval-batch-', REPLACE(UUID(), '-', '')),
       r.department_id, r.department_key, r.department_name, 'DAILY', r.business_date,
       CASE WHEN r.status = 'APPROVED' THEN 'APPROVED' ELSE 'PENDING' END,
       1,
       COUNT(l.id),
       COALESCE(SUM(CASE WHEN l.review_status = 'APPROVED' THEN 1 ELSE 0 END), 0),
       COALESCE(SUM(CASE WHEN l.review_status = 'REJECTED' THEN 1 ELSE 0 END), 0)
FROM inventory_department_daily_reports r
LEFT JOIN inventory_department_daily_report_lines l ON l.report_id = r.id
WHERE r.status IN ('SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED')
  AND NOT EXISTS (
    SELECT 1 FROM inventory_consumption_approval_batches b
    WHERE b.department_id = r.department_id AND b.business_date = r.business_date
  )
GROUP BY r.id, r.department_id, r.department_key, r.department_name, r.business_date, r.status;
