CREATE TABLE inventory_portal_accounts (
  id VARCHAR(80) PRIMARY KEY,
  username VARCHAR(80) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  department_key VARCHAR(80) NOT NULL,
  department_name VARCHAR(80) NOT NULL,
  clinic_role VARCHAR(80) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  status VARCHAR(20) NOT NULL DEFAULT '启用',
  display_order INT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_inventory_portal_accounts_username (username),
  KEY idx_inventory_portal_accounts_status (status, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_portal_sessions (
  token_hash CHAR(64) PRIMARY KEY,
  user_id VARCHAR(80) NOT NULL,
  username VARCHAR(80) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  role VARCHAR(80) NOT NULL,
  role_label VARCHAR(80) NOT NULL,
  active_department_id VARCHAR(80) NOT NULL,
  department_name VARCHAR(80) NOT NULL,
  must_change_password BOOLEAN NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  revoke_reason VARCHAR(80) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_inventory_portal_sessions_user (user_id, revoked_at, expires_at),
  KEY idx_inventory_portal_sessions_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_portal_login_failures (
  failure_key_hash CHAR(64) PRIMARY KEY,
  username VARCHAR(80) NOT NULL,
  remote_address VARCHAR(128) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME(6) NULL,
  last_failed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO inventory_portal_accounts
  (id, username, display_name, department_key, department_name, clinic_role, password_hash, must_change_password, status, display_order)
VALUES
  ('inventory-portal-physiotherapy', 'inv-physiotherapy', '理疗室', 'physiotherapy', '理疗室', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 1),
  ('inventory-portal-tcm', 'inv-tcm', '中医科', 'tcm', '中医科', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 2),
  ('inventory-portal-tcm-pharmacy', 'inv-tcm-pharmacy', '中药房', 'tcm-pharmacy', '中药房', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 3),
  ('inventory-portal-logistics', 'inv-logistics', '后勤', 'logistics', '后勤', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 4),
  ('inventory-portal-western-pharmacy', 'inv-western-pharmacy', '西药房', 'western-pharmacy', '西药房', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 5),
  ('inventory-portal-operating', 'inv-operating', '手术室', 'operating', '手术室', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 6),
  ('inventory-portal-nursing', 'inv-nursing', '护理部', 'nursing', '护理部', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 7),
  ('inventory-portal-cashier', 'inv-cashier', '收费室', 'cashier', '收费室', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 8),
  ('inventory-portal-inspection', 'inv-inspection', '检查室', 'inspection', '检查室', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 9),
  ('inventory-portal-laboratory', 'inv-laboratory', '检验科', 'laboratory', '检验科', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 10),
  ('inventory-portal-endoscopy', 'inv-endoscopy', '胃肠镜', 'endoscopy', '胃肠镜', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 11),
  ('inventory-portal-anesthesia', 'inv-anesthesia', '麻醉室', 'anesthesia', '麻醉室', 'inventory_reporter', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 12),
  ('inventory-portal-admin', 'inv-admin', '进销存管理员', 'inventory-admin', '管理端', 'admin', '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 13);

INSERT INTO inventory_account_roles (account_id, role_code, assigned_by)
VALUES
  ('inventory-portal-physiotherapy', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-tcm', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-tcm-pharmacy', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-logistics', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-western-pharmacy', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-operating', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-nursing', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-cashier', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-inspection', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-laboratory', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-endoscopy', 'inventory_department_reporter', 'inventory-portal-bootstrap'),
  ('inventory-portal-anesthesia', 'inventory_department_reporter', 'inventory-portal-bootstrap')
ON DUPLICATE KEY UPDATE role_code = VALUES(role_code), assigned_by = VALUES(assigned_by), updated_at = CURRENT_TIMESTAMP;
