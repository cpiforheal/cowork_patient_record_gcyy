SET @add_client_request_id = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'clinic_queue_print_tasks' AND column_name = 'client_request_id') = 0,
  'ALTER TABLE clinic_queue_print_tasks ADD COLUMN client_request_id VARCHAR(100) NULL AFTER ticket_id',
  'SELECT 1'
);
PREPARE add_client_request_id_stmt FROM @add_client_request_id;
EXECUTE add_client_request_id_stmt;
DEALLOCATE PREPARE add_client_request_id_stmt;

UPDATE clinic_queue_print_tasks
SET client_request_id = CONCAT('legacy-', id)
WHERE client_request_id IS NULL OR client_request_id = '';

ALTER TABLE clinic_queue_print_tasks MODIFY client_request_id VARCHAR(100) NOT NULL;

SET @add_client_request_unique = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'clinic_queue_print_tasks'
     AND index_name = 'uq_clinic_queue_print_client_request') = 0,
  'ALTER TABLE clinic_queue_print_tasks ADD UNIQUE INDEX uq_clinic_queue_print_client_request (client_request_id)',
  'SELECT 1'
);
PREPARE add_client_request_unique_stmt FROM @add_client_request_unique;
EXECUTE add_client_request_unique_stmt;
DEALLOCATE PREPARE add_client_request_unique_stmt;
