-- V50: 医疗质量安全核心制度学习模块（一期）
-- 版本号 V48/V49 预留给 feat/staging-9848 分支的随访迁移（已应用于试验库，暂未合并主干）
-- 纯新增表，幂等可重入；生产库（迁移 47）启动时自动应用。
-- 时间戳沿用本库惯例存 VARCHAR(32)（yyyy-MM-dd HH:mm:ss）。

CREATE TABLE IF NOT EXISTS clinic_core_policy (
  id VARCHAR(64) PRIMARY KEY,
  code VARCHAR(32) NOT NULL COMMENT '制度编号 01-18',
  title VARCHAR(200) NOT NULL COMMENT '制度名称',
  summary VARCHAR(1000) NOT NULL COMMENT '定义/摘要',
  content_version VARCHAR(32) NOT NULL DEFAULT 'v1-2018' COMMENT '内容包版本',
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at VARCHAR(32),
  updated_at VARCHAR(32),
  UNIQUE KEY uk_core_policy_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='医疗质量安全核心制度';

CREATE TABLE IF NOT EXISTS clinic_core_policy_clause (
  id VARCHAR(64) PRIMARY KEY,
  policy_id VARCHAR(64) NOT NULL,
  clause_no VARCHAR(16) NOT NULL DEFAULT '' COMMENT '条款序号（制度内）',
  content MEDIUMTEXT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at VARCHAR(32),
  INDEX idx_core_clause_policy (policy_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核心制度基本要求条款';

CREATE TABLE IF NOT EXISTS clinic_core_policy_check_in (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  user_name VARCHAR(100) NOT NULL DEFAULT '',
  policy_id VARCHAR(64) NOT NULL,
  plan_date VARCHAR(10) NOT NULL COMMENT ' yyyy-MM-dd',
  checked_at VARCHAR(32),
  UNIQUE KEY uk_core_checkin_user_date (user_id, plan_date),
  INDEX idx_core_checkin_policy (policy_id, plan_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日一条打卡';

CREATE TABLE IF NOT EXISTS clinic_core_policy_quiz_question (
  id VARCHAR(64) PRIMARY KEY,
  policy_id VARCHAR(64) NOT NULL,
  clause_id VARCHAR(64) NOT NULL DEFAULT '',
  question_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE' COMMENT 'SINGLE/JUDGE',
  stem VARCHAR(1000) NOT NULL,
  options_json JSON NOT NULL,
  answer VARCHAR(8) NOT NULL COMMENT 'A/B/C/D 或对错字母',
  explanation VARCHAR(1000) NOT NULL DEFAULT '',
  generated_by VARCHAR(20) NOT NULL DEFAULT 'hand',
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at VARCHAR(32),
  INDEX idx_core_quiz_question_policy (policy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核心制度题库';

CREATE TABLE IF NOT EXISTS clinic_core_policy_quiz_attempt (
  id VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  user_name VARCHAR(100) NOT NULL DEFAULT '',
  quiz_month VARCHAR(7) NOT NULL COMMENT ' yyyy-MM',
  question_ids_json JSON NOT NULL,
  answers_json JSON NOT NULL,
  score INT NOT NULL DEFAULT 0,
  total INT NOT NULL DEFAULT 0,
  submitted_at VARCHAR(32),
  UNIQUE KEY uk_core_quiz_user_month (user_id, quiz_month),
  INDEX idx_core_quiz_month (quiz_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每月小测答题记录';
