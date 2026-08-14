ALTER TABLE clinic_departments
  ADD COLUMN code VARCHAR(64) NULL AFTER id,
  ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' AFTER name;

UPDATE clinic_departments
SET code = id,
    status = CASE
      WHEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.status')), '启用') IN ('启用', 'ACTIVE') THEN 'ACTIVE'
      ELSE 'INACTIVE'
    END;

ALTER TABLE clinic_departments
  MODIFY COLUMN code VARCHAR(64) NOT NULL,
  ADD UNIQUE KEY uk_clinic_departments_code (code);

INSERT IGNORE INTO clinic_departments (id, code, name, status, raw_json)
SELECT
  CONCAT('legacy-dept-', LEFT(SHA2(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department'))), 256), 32)),
  CONCAT('LEGACY-', LEFT(SHA2(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department'))), 256), 16)),
  TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department'))),
  'ACTIVE',
  JSON_OBJECT(
    'id', CONCAT('legacy-dept-', LEFT(SHA2(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department'))), 256), 32)),
    'code', CONCAT('LEGACY-', LEFT(SHA2(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department'))), 256), 16)),
    'name', TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department'))),
    'status', 'ACTIVE',
    'scope', '由历史账号科室名称迁移'
  )
FROM clinic_accounts a
WHERE COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department'))), '') <> ''
  AND NOT EXISTS (
    SELECT 1
    FROM clinic_departments existing_department
    WHERE existing_department.name = TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department')))
  );

CREATE TABLE clinic_account_departments (
  account_id VARCHAR(64) NOT NULL,
  department_id VARCHAR(64) NOT NULL,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (account_id, department_id),
  INDEX idx_account_departments_department (department_id, status),
  INDEX idx_account_departments_primary (account_id, is_primary, status),
  CONSTRAINT fk_account_departments_account FOREIGN KEY (account_id) REFERENCES clinic_accounts(id) ON DELETE CASCADE,
  CONSTRAINT fk_account_departments_department FOREIGN KEY (department_id) REFERENCES clinic_departments(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO clinic_account_departments (account_id, department_id, is_primary, status)
SELECT a.id, d.id, TRUE, 'ACTIVE'
FROM clinic_accounts a
JOIN (
  SELECT name, MIN(id) AS id
  FROM clinic_departments
  GROUP BY name
) d
  ON d.name = TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department')))
WHERE COALESCE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.raw_json, '$.department'))), '') <> '';

ALTER TABLE clinic_auth_sessions
  ADD COLUMN active_department_id VARCHAR(64) NULL AFTER department,
  ADD INDEX idx_auth_sessions_active_department (active_department_id),
  ADD CONSTRAINT fk_auth_sessions_active_department FOREIGN KEY (active_department_id) REFERENCES clinic_departments(id) ON DELETE RESTRICT;

UPDATE clinic_auth_sessions s
JOIN clinic_account_departments ad ON ad.account_id = s.user_id AND ad.is_primary = TRUE AND ad.status = 'ACTIVE'
SET s.active_department_id = ad.department_id
WHERE s.active_department_id IS NULL;

INSERT IGNORE INTO clinic_departments (id, code, name, status, raw_json)
VALUES (
  'dept-unassigned',
  'UNASSIGNED',
  '待确认归属',
  'ACTIVE',
  JSON_OBJECT(
    'id', 'dept-unassigned',
    'code', 'UNASSIGNED',
    'name', '待确认归属',
    'status', 'ACTIVE',
    'scope', '历史病历无法确定归属时的迁移兜底；上线后由管理员修正'
  )
);

-- 历史账号未记录科室时仍需能登录完成归属修正，但不得以名称字符串隐式授权。
INSERT IGNORE INTO clinic_account_departments (account_id, department_id, is_primary, status)
SELECT a.id, 'dept-unassigned', TRUE, 'ACTIVE'
FROM clinic_accounts a
WHERE NOT EXISTS (
  SELECT 1 FROM clinic_account_departments ad WHERE ad.account_id = a.id AND ad.status = 'ACTIVE'
);

UPDATE clinic_auth_sessions s
JOIN clinic_account_departments ad ON ad.account_id = s.user_id AND ad.is_primary = TRUE AND ad.status = 'ACTIVE'
SET s.active_department_id = ad.department_id
WHERE s.active_department_id IS NULL;

ALTER TABLE pre_ai_encounters
  ADD COLUMN owning_department_id VARCHAR(64) NULL AFTER patient_case_id,
  ADD COLUMN owning_department_name_snapshot VARCHAR(100) NULL AFTER owning_department_id,
  ADD INDEX idx_pre_ai_encounter_department (owning_department_id, status),
  ADD CONSTRAINT fk_pre_ai_encounter_department FOREIGN KEY (owning_department_id) REFERENCES clinic_departments(id) ON DELETE RESTRICT;

UPDATE pre_ai_encounters e
JOIN (
  SELECT name, MIN(id) AS id
  FROM clinic_departments
  GROUP BY name
) department_match ON department_match.name = COALESCE(
  NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.visit_meta_json, '$.department')), ''),
  NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.department')), ''),
  NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.patient_json, '$.departmentName')), '')
)
JOIN clinic_departments d ON d.id = department_match.id
SET e.owning_department_id = department_match.id,
    e.owning_department_name_snapshot = department_match.name
WHERE e.owning_department_id IS NULL;

UPDATE pre_ai_encounters e
JOIN (
  SELECT identity_value, MIN(account_id) AS account_id
  FROM (
    SELECT id AS account_id, TRIM(id) AS identity_value FROM clinic_accounts
    UNION ALL
    SELECT id AS account_id, TRIM(username) AS identity_value FROM clinic_accounts
    UNION ALL
    SELECT id AS account_id, TRIM(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.name'))) AS identity_value FROM clinic_accounts
    UNION ALL
    SELECT id AS account_id, TRIM(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.displayName'))) AS identity_value FROM clinic_accounts
  ) account_identities
  WHERE COALESCE(identity_value, '') <> ''
  GROUP BY identity_value
) account_match ON account_match.identity_value = TRIM(e.created_by)
JOIN (
  SELECT account_id, MIN(department_id) AS department_id
  FROM clinic_account_departments
  WHERE is_primary = TRUE AND status = 'ACTIVE'
  GROUP BY account_id
) primary_department ON primary_department.account_id = account_match.account_id
JOIN clinic_departments d ON d.id = primary_department.department_id
SET e.owning_department_id = d.id,
    e.owning_department_name_snapshot = d.name
WHERE e.owning_department_id IS NULL;

UPDATE pre_ai_encounters
SET owning_department_id = 'dept-unassigned',
    owning_department_name_snapshot = '待确认归属'
WHERE owning_department_id IS NULL;

ALTER TABLE pre_ai_encounters
  MODIFY COLUMN owning_department_id VARCHAR(64) NOT NULL;

CREATE TABLE pre_ai_encounter_department_grants (
  account_id VARCHAR(64) NOT NULL,
  encounter_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  granted_by VARCHAR(64) NULL,
  reason VARCHAR(500) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (account_id, encounter_id),
  INDEX idx_pre_ai_department_grants_encounter (encounter_id, status),
  CONSTRAINT fk_pre_ai_department_grants_account FOREIGN KEY (account_id) REFERENCES clinic_accounts(id) ON DELETE CASCADE,
  CONSTRAINT fk_pre_ai_department_grants_encounter FOREIGN KEY (encounter_id) REFERENCES pre_ai_encounters(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE pre_ai_audit_logs
  ADD COLUMN reason VARCHAR(500) NULL AFTER detail,
  ADD COLUMN before_json JSON NULL AFTER reason,
  ADD COLUMN after_json JSON NULL AFTER before_json;
