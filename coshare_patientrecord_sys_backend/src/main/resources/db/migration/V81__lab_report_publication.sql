CREATE TABLE IF NOT EXISTS clinic_lab_portal_publications (
  id VARCHAR(64) PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL,
  encounter_id VARCHAR(64) NOT NULL,
  report_version INT NOT NULL DEFAULT 1,
  token_hash CHAR(64) NOT NULL,
  payload_json LONGTEXT NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  revoked_by VARCHAR(100) NULL,
  push_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  last_error VARCHAR(500) NULL,
  published_by VARCHAR(100) NOT NULL,
  published_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_lab_portal_publication_token (token_hash),
  KEY idx_lab_portal_publication_report (report_id, revoked_at),
  KEY idx_lab_portal_publication_push (push_status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_portal_reports (
  id VARCHAR(64) PRIMARY KEY,
  token_hash CHAR(64) NOT NULL,
  patient_name VARCHAR(80) NOT NULL,
  gender VARCHAR(16) NULL,
  age VARCHAR(16) NULL,
  visit_no INT NULL,
  case_token_masked VARCHAR(64) NULL,
  report_date VARCHAR(32) NULL,
  template_name VARCHAR(255) NULL,
  metrics_json LONGTEXT NOT NULL,
  critical_count INT NOT NULL DEFAULT 0,
  expires_at DATETIME(3) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_lab_portal_report_token (token_hash),
  KEY idx_lab_portal_report_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lab_portal_view_audits (
  id VARCHAR(64) PRIMARY KEY,
  report_id VARCHAR(64) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  ip VARCHAR(64) NULL,
  user_agent VARCHAR(255) NULL,
  viewed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_lab_portal_audit_time (viewed_at),
  KEY idx_lab_portal_audit_report (report_id, viewed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
