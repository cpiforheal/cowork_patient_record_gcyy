-- Converge persisted account identities before adding the case-insensitive username constraint.
UPDATE clinic_accounts
SET username = CONCAT('account-', LEFT(SHA2(id, 256), 16)),
    raw_json = JSON_SET(raw_json, '$.username', CONCAT('account-', LEFT(SHA2(id, 256), 16)))
WHERE COALESCE(TRIM(username), '') = '';

CREATE TEMPORARY TABLE clinic_username_keepers AS
SELECT
  LOWER(username) AS normalized_username,
  SUBSTRING_INDEX(
    GROUP_CONCAT(id ORDER BY (status = '启用') DESC, id SEPARATOR ','),
    ',',
    1
  ) AS keep_id
FROM clinic_accounts
GROUP BY LOWER(username)
HAVING COUNT(*) > 1;

UPDATE clinic_accounts account_row
JOIN clinic_username_keepers duplicate_group
  ON LOWER(account_row.username) = duplicate_group.normalized_username
 AND account_row.id <> duplicate_group.keep_id
SET account_row.raw_json = JSON_SET(
      account_row.raw_json,
      '$.username', CONCAT(LEFT(account_row.username, 76), '-duplicate-', LEFT(SHA2(account_row.id, 256), 8)),
      '$.status', '停用',
      '$.scope', '历史重复账号，已由系统停用并改名'
    ),
    account_row.status = '停用',
    account_row.username = CONCAT(LEFT(account_row.username, 76), '-duplicate-', LEFT(SHA2(account_row.id, 256), 8));

DROP TEMPORARY TABLE clinic_username_keepers;

UPDATE clinic_accounts
SET role = CASE LOWER(role)
      WHEN 'nursing' THEN 'nurse'
      WHEN 'tcmpharmacyoperator' THEN 'tcm_pharmacy'
      WHEN 'pharmacist' THEN 'tcm_pharmacy'
      WHEN 'pharmacy' THEN 'tcm_pharmacy'
      WHEN 'decoction' THEN 'tcm_pharmacy'
      ELSE LOWER(role)
    END;

UPDATE clinic_accounts
SET raw_json = JSON_SET(
  raw_json,
  '$.role', role,
  '$.roleLabel', CASE role
    WHEN 'admin' THEN '系统管理员'
    WHEN 'manager' THEN '管理负责人'
    WHEN 'quality' THEN '质控与病案'
    WHEN 'display' THEN '展示终端'
    WHEN 'frontdesk' THEN '登记前台'
    WHEN 'reception' THEN '接诊岗位'
    WHEN 'inspection' THEN '检查岗位'
    WHEN 'tcm' THEN '中医岗位'
    WHEN 'doctor' THEN '医生岗位'
    WHEN 'nurse' THEN '护理与手术'
    WHEN 'lab' THEN '检验岗位'
    WHEN 'ecg' THEN '心电岗位'
    WHEN 'ultrasound' THEN '超声岗位'
    WHEN 'warehouse' THEN '仓库岗位'
    WHEN 'tcm_pharmacy' THEN '中药房岗位'
    ELSE '待收敛岗位'
  END
);

ALTER TABLE clinic_accounts
  MODIFY COLUMN username VARCHAR(100) NOT NULL,
  ADD UNIQUE KEY uk_clinic_accounts_username (username);

-- clinic_roles is display metadata only. Runtime permissions remain server-owned.
DELETE FROM clinic_roles;

INSERT INTO clinic_roles (id, role, name, raw_json) VALUES
  ('role-admin', 'admin', '系统管理员', JSON_OBJECT('id','role-admin','role','admin','name','系统管理员')),
  ('role-manager', 'manager', '管理负责人', JSON_OBJECT('id','role-manager','role','manager','name','管理负责人')),
  ('role-quality', 'quality', '质控与病案', JSON_OBJECT('id','role-quality','role','quality','name','质控与病案')),
  ('role-display', 'display', '展示终端', JSON_OBJECT('id','role-display','role','display','name','展示终端')),
  ('role-frontdesk', 'frontdesk', '登记前台', JSON_OBJECT('id','role-frontdesk','role','frontdesk','name','登记前台')),
  ('role-reception', 'reception', '接诊岗位', JSON_OBJECT('id','role-reception','role','reception','name','接诊岗位')),
  ('role-inspection', 'inspection', '检查岗位', JSON_OBJECT('id','role-inspection','role','inspection','name','检查岗位')),
  ('role-tcm', 'tcm', '中医岗位', JSON_OBJECT('id','role-tcm','role','tcm','name','中医岗位')),
  ('role-doctor', 'doctor', '医生岗位', JSON_OBJECT('id','role-doctor','role','doctor','name','医生岗位')),
  ('role-nurse', 'nurse', '护理与手术', JSON_OBJECT('id','role-nurse','role','nurse','name','护理与手术')),
  ('role-lab', 'lab', '检验岗位', JSON_OBJECT('id','role-lab','role','lab','name','检验岗位')),
  ('role-ecg', 'ecg', '心电岗位', JSON_OBJECT('id','role-ecg','role','ecg','name','心电岗位')),
  ('role-ultrasound', 'ultrasound', '超声岗位', JSON_OBJECT('id','role-ultrasound','role','ultrasound','name','超声岗位')),
  ('role-warehouse', 'warehouse', '仓库岗位', JSON_OBJECT('id','role-warehouse','role','warehouse','name','仓库岗位')),
  ('role-tcm-pharmacy', 'tcm_pharmacy', '中药房岗位', JSON_OBJECT('id','role-tcm-pharmacy','role','tcm_pharmacy','name','中药房岗位'));

CREATE TABLE clinic_data_purge_runs (
  run_id VARCHAR(64) PRIMARY KEY,
  operator_id VARCHAR(64) NOT NULL,
  operator_name VARCHAR(100) NOT NULL,
  status VARCHAR(32) NOT NULL,
  backup_dir VARCHAR(1024) NULL,
  backup_sha256 CHAR(64) NULL,
  before_counts_json JSON NULL,
  after_counts_json JSON NULL,
  error_message TEXT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  database_committed BOOLEAN NOT NULL DEFAULT FALSE,
  files_quarantined BOOLEAN NOT NULL DEFAULT FALSE,
  INDEX idx_data_purge_runs_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
