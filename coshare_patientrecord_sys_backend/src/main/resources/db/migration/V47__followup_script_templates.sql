CREATE TABLE IF NOT EXISTS clinic_followup_script_templates (
  id VARCHAR(64) PRIMARY KEY,
  node_key VARCHAR(64) NOT NULL,
  label VARCHAR(100) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  sort_order INT NOT NULL DEFAULT 0,
  created_by VARCHAR(100),
  created_at VARCHAR(32),
  updated_by VARCHAR(100),
  updated_at VARCHAR(32),
  INDEX idx_followup_script_status (status),
  INDEX idx_followup_script_node (node_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
