-- AI 病种归类标签缓存：按患者缓存主诉归类结果，统计时与模板明确分类合并
CREATE TABLE IF NOT EXISTS pre_ai_disease_tags (
              patient_case_id VARCHAR(64) PRIMARY KEY,
              tags_json JSON NOT NULL,
              ai_model VARCHAR(64) NOT NULL,
              tagged_at VARCHAR(32) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
