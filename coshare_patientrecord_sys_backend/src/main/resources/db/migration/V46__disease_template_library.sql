-- 病种模板库（热更新）：预置病种模板下沉数据库，支持管理端热编辑、通用路径孵化与转正
CREATE TABLE IF NOT EXISTS clinic_disease_templates (
              id VARCHAR(64) PRIMARY KEY,
              disease VARCHAR(100) NOT NULL,
              label VARCHAR(100) NOT NULL,
              version VARCHAR(32) NOT NULL DEFAULT 'anorectal-v2.0',
              status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
              payload_json JSON NOT NULL,
              usage_count INT NOT NULL DEFAULT 0,
              last_encounter_id VARCHAR(64),
              ready_to_promote TINYINT(1) NOT NULL DEFAULT 0,
              created_by VARCHAR(100),
              created_at VARCHAR(32),
              updated_by VARCHAR(100),
              updated_at VARCHAR(32),
              INDEX idx_clinic_disease_templates_status (status),
              INDEX idx_clinic_disease_templates_disease (disease)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
