-- Inventory access is intentionally independent from a user's clinical post.
-- A clinical account keeps its existing role and departments; administrators assign a separate inventory role here.
CREATE TABLE IF NOT EXISTS inventory_account_roles (
  account_id VARCHAR(64) PRIMARY KEY,
  role_code VARCHAR(64) NOT NULL,
  assigned_by VARCHAR(64),
  assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_inventory_account_roles_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
