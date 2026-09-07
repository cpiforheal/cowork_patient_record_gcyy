-- 医政早报：每日定时采集的医疗政策 / 医保DIP / 肛肠学术资讯与 AI 摘要（管理端资讯页）
CREATE TABLE IF NOT EXISTS policy_brief_item (
              id VARCHAR(64) PRIMARY KEY,
              brief_date VARCHAR(10) NOT NULL,
              source_name VARCHAR(64) NOT NULL,
              category VARCHAR(24) NOT NULL,
              title VARCHAR(512) NOT NULL,
              url VARCHAR(900) NOT NULL,
              published_at VARCHAR(32),
              ai_summary VARCHAR(1024),
              ai_model VARCHAR(64),
              status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
              content_hash CHAR(64) NOT NULL,
              created_at VARCHAR(32) NOT NULL,
              UNIQUE KEY uk_policy_brief_hash (content_hash),
              INDEX idx_policy_brief_date (brief_date, category)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
