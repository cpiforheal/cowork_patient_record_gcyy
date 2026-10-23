CREATE TABLE IF NOT EXISTS pre_ai_review_imaging_notes (
  id VARCHAR(64) PRIMARY KEY,
  encounter_id VARCHAR(64) NOT NULL,
  category VARCHAR(32) NOT NULL,
  note TEXT NOT NULL,
  created_at VARCHAR(32) NOT NULL,
  created_by VARCHAR(100),
  created_by_role VARCHAR(64),
  updated_at VARCHAR(32) NOT NULL,
  updated_by VARCHAR(100),
  updated_by_role VARCHAR(64),
  UNIQUE KEY uk_pre_ai_review_imaging_note (encounter_id, category),
  INDEX idx_pre_ai_review_imaging_note_encounter (encounter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
