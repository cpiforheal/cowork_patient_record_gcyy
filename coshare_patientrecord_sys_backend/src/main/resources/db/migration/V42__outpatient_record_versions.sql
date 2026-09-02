-- 门诊病历版本表：医生复核确认后，由医生触发生成门诊病历 DOCX（独立于住院模板与脱敏导出）
CREATE TABLE IF NOT EXISTS pre_ai_outpatient_records (
              id VARCHAR(64) PRIMARY KEY,
              encounter_id VARCHAR(64) NOT NULL,
              version INT NOT NULL,
              status VARCHAR(32) NOT NULL,
              case_token VARCHAR(64) NOT NULL,
              file_name VARCHAR(255) NOT NULL,
              file_path VARCHAR(700) NOT NULL,
              source_snapshot JSON NOT NULL,
              generated_by VARCHAR(100),
              generated_by_role VARCHAR(64),
              generated_at VARCHAR(32) NOT NULL,
              INDEX idx_pre_ai_outpatient_encounter (encounter_id, version)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
