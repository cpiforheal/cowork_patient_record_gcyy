INSERT INTO inventory_portal_accounts
  (id, username, display_name, department_key, department_name, clinic_role, password_hash, must_change_password, status, display_order)
VALUES
  ('inventory-portal-warehouse', 'inv-warehouse', '中心库', 'inventory-warehouse', '中心库', 'inventory_warehouse',
   '$2a$10$VBnlxgOxf9J1GvElbwd3auNS6dDpKc622tm1qri59vi5WnexvoSZG', TRUE, '启用', 0)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), department_name = VALUES(department_name);

INSERT INTO inventory_account_roles (account_id, role_code, assigned_by)
VALUES ('inventory-portal-warehouse', 'inventory_warehouse', 'inventory-portal-bootstrap')
ON DUPLICATE KEY UPDATE role_code = VALUES(role_code), assigned_by = VALUES(assigned_by), updated_at = CURRENT_TIMESTAMP;
