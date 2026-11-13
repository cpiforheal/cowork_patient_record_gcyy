CREATE TABLE IF NOT EXISTS clinic_lab_portal_sync (
  encounter_id VARCHAR(64) PRIMARY KEY,
  identity_key CHAR(64) NULL,
  identity_type VARCHAR(8) NULL,
  payload_json LONGTEXT NULL,
  report_count INT NOT NULL DEFAULT 0,
  push_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  last_error VARCHAR(500) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_lab_portal_sync_push (push_status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_portal_patient_reports (
  id VARCHAR(64) PRIMARY KEY,
  identity_key CHAR(64) NOT NULL,
  encounter_id VARCHAR(64) NOT NULL,
  report_id VARCHAR(64) NOT NULL,
  visit_no INT NOT NULL DEFAULT 1,
  visit_date DATE NOT NULL,
  report_date VARCHAR(32) NULL,
  template_name VARCHAR(255) NULL,
  metrics_json LONGTEXT NOT NULL,
  abnormal_count INT NOT NULL DEFAULT 0,
  critical_count INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_lab_portal_patient_report (identity_key, report_id),
  KEY idx_lab_portal_patient_visit (identity_key, visit_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_portal_access_logs (
  id VARCHAR(64) PRIMARY KEY,
  identity_key CHAR(64) NULL,
  action VARCHAR(16) NOT NULL,
  success TINYINT NOT NULL DEFAULT 1,
  ip VARCHAR(64) NULL,
  user_agent VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_lab_portal_access_time (created_at),
  KEY idx_lab_portal_access_identity (identity_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
