-- DIP 费用预警：HIS 侧字段并入费用快照 + 病组人工改选
-- 背景：本系统已有姓名/性别/年龄/主诊断/入院日期；住院号、病人类型、床号、押金、护理级别只存在于 HIS，
-- 在录入 HIS 费用时一并记录，使列表字段与 HIS 住院费用界面保持一致。

ALTER TABLE dip_fee_snapshot
  ADD COLUMN admission_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '住院号（HIS）',
  ADD COLUMN patient_type VARCHAR(64) NOT NULL DEFAULT '' COMMENT '病人类型（HIS，如 国家医保（居民））',
  ADD COLUMN bed_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '床号（HIS）',
  ADD COLUMN nursing_level VARCHAR(32) NOT NULL DEFAULT '' COMMENT '护理级别（HIS）',
  ADD COLUMN deposit_total DECIMAL(12,2) NULL COMMENT '押金总额（HIS）',
  ADD COLUMN deposit_balance DECIMAL(12,2) NULL COMMENT '押金余额（HIS，负数表示欠费）',
  ADD COLUMN admission_date VARCHAR(32) NOT NULL DEFAULT '' COMMENT '入院日期（HIS）',
  ADD COLUMN attending_doctor VARCHAR(100) NOT NULL DEFAULT '' COMMENT '管床医生（HIS）',
  ADD COLUMN remark VARCHAR(200) NOT NULL DEFAULT '' COMMENT '录入备注';

-- 病组人工改选：医生确定术式后可改选病组，费用标准随之变化；置空则回落到主诊断自动匹配
CREATE TABLE IF NOT EXISTS dip_group_selection (
  patient_id VARCHAR(64) PRIMARY KEY COMMENT 'clinic_patients.id',
  group_code VARCHAR(64) NOT NULL COMMENT 'dip_group_mapping.group_code',
  selection_note VARCHAR(200) NOT NULL DEFAULT '',
  updated_by VARCHAR(100) NOT NULL DEFAULT '',
  updated_at VARCHAR(32) NOT NULL DEFAULT '',
  INDEX idx_dip_selection_group (group_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
