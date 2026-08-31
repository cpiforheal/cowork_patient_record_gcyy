-- 复诊随访：检查室创建，后置科室可查看；独立于前置病历导出与 AI 成档数据链路
CREATE TABLE IF NOT EXISTS pre_ai_follow_up_visits (
  id VARCHAR(64) PRIMARY KEY,
  encounter_id VARCHAR(64) NOT NULL,
  seq INT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  condition_note MEDIUMTEXT,
  next_review_date VARCHAR(32),
  status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
  created_by VARCHAR(100),
  created_by_role VARCHAR(64),
  created_at VARCHAR(32) NOT NULL,
  updated_by VARCHAR(100),
  updated_by_role VARCHAR(64),
  updated_at VARCHAR(32) NOT NULL,
  UNIQUE KEY uq_follow_up_seq (encounter_id, seq),
  INDEX idx_follow_up_encounter (encounter_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pre_ai_follow_up_images (
  id VARCHAR(64) PRIMARY KEY,
  visit_id VARCHAR(64) NOT NULL,
  encounter_id VARCHAR(64) NOT NULL,
  seq INT NOT NULL DEFAULT 1,
  file_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(700) NOT NULL,
  mime_type VARCHAR(128),
  file_size BIGINT DEFAULT 0,
  created_by VARCHAR(100),
  created_at VARCHAR(32) NOT NULL,
  UNIQUE KEY uq_follow_up_img_seq (visit_id, seq),
  INDEX idx_follow_up_img_visit (visit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
