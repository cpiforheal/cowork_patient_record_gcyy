CREATE TABLE IF NOT EXISTS pre_ai_admission_profiles (
  encounter_id VARCHAR(64) PRIMARY KEY,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  data_json JSON NOT NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  created_by VARCHAR(100) NOT NULL DEFAULT '',
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(100) NOT NULL DEFAULT '',
  completed_at DATETIME NULL,
  completed_by VARCHAR(100) NOT NULL DEFAULT '',
  CONSTRAINT fk_pre_ai_admission_profile_encounter FOREIGN KEY (encounter_id) REFERENCES pre_ai_encounters(id),
  INDEX idx_pre_ai_admission_profile_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
