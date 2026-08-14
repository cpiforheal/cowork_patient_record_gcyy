CREATE TABLE IF NOT EXISTS clinic_auth_sessions (
  token_hash CHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  username VARCHAR(100) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  role VARCHAR(64) NOT NULL,
  role_label VARCHAR(100),
  department VARCHAR(100),
  must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  expires_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  revoked_at TIMESTAMP(6) NULL,
  revoke_reason VARCHAR(64) NULL,
  INDEX idx_clinic_auth_sessions_user (user_id, revoked_at),
  INDEX idx_clinic_auth_sessions_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_login_failures (
  failure_key_hash CHAR(64) PRIMARY KEY,
  username VARCHAR(100) NOT NULL,
  remote_address VARCHAR(128) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP(6) NULL,
  last_failed_at TIMESTAMP(6) NOT NULL,
  INDEX idx_clinic_login_failures_last_failed (last_failed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @add_must_change_password = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'clinic_auth_sessions' AND column_name = 'must_change_password') = 0,
  'ALTER TABLE clinic_auth_sessions ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE AFTER department',
  'SELECT 1'
);
PREPARE add_must_change_password_stmt FROM @add_must_change_password;
EXECUTE add_must_change_password_stmt;
DEALLOCATE PREPARE add_must_change_password_stmt;

UPDATE clinic_accounts
SET raw_json = JSON_REMOVE(raw_json, '$.password', '$.currentPassword')
WHERE JSON_CONTAINS_PATH(raw_json, 'one', '$.password', '$.currentPassword');

UPDATE clinic_accounts
SET raw_json = JSON_REMOVE(raw_json, '$.passwordHash')
WHERE JSON_CONTAINS_PATH(raw_json, 'one', '$.passwordHash')
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.passwordHash')) NOT REGEXP '^\\$2[aby]\\$';
