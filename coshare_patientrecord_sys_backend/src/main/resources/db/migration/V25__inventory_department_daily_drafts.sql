CREATE TABLE inventory_department_daily_drafts (
  id VARCHAR(64) PRIMARY KEY,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  business_date DATE NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  revision INT NOT NULL DEFAULT 1,
  operator_name VARCHAR(120) NOT NULL,
  raw_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_department_daily_draft (department_key, business_date),
  INDEX idx_inventory_department_daily_draft_date (business_date, department_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
