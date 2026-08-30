-- 健康管理档案：草稿（每就诊一份）与合并文档（每就诊多版本）
CREATE TABLE IF NOT EXISTS health_archive_drafts (
  id VARCHAR(64) PRIMARY KEY,
  encounter_id VARCHAR(64) NOT NULL,
  source_record_id VARCHAR(128),
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  archive_no VARCHAR(64),
  form_json JSON NOT NULL,
  revision INT NOT NULL DEFAULT 0,
  created_by VARCHAR(100),
  created_by_role VARCHAR(64),
  created_at VARCHAR(32) NOT NULL,
  updated_by VARCHAR(100),
  updated_by_role VARCHAR(64),
  updated_at VARCHAR(32) NOT NULL,
  completed_at VARCHAR(32),
  completed_by VARCHAR(100),
  completed_by_role VARCHAR(64),
  UNIQUE KEY uq_health_archive_draft_encounter (encounter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS health_archive_documents (
  id VARCHAR(64) PRIMARY KEY,
  encounter_id VARCHAR(64) NOT NULL,
  draft_id VARCHAR(64) NOT NULL,
  source_record_id VARCHAR(128),
  version INT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(700) NOT NULL,
  sha256 VARCHAR(128),
  file_size BIGINT DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_by VARCHAR(100),
  created_by_role VARCHAR(64),
  created_at VARCHAR(32) NOT NULL,
  UNIQUE KEY uq_health_archive_doc (encounter_id, version),
  INDEX idx_health_archive_doc_encounter (encounter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
