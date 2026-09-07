-- 医政早报每日综述：采集完成后由 AI 将当日各条要点汇总为一段"今日综述"
CREATE TABLE IF NOT EXISTS policy_brief_daily (
              brief_date VARCHAR(10) PRIMARY KEY,
              digest TEXT,
              item_count INT,
              ai_model VARCHAR(64),
              generated_at VARCHAR(32) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
