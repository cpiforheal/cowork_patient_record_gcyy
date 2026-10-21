-- DIP 费用预警工作台：病组目录治理 + 患者级费用覆盖 + 费用作废 + 完整变更历史
-- 纯增量 DDL：不改已有列类型，不迁移历史数据。

ALTER TABLE dip_group_mapping
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 启用 / DISABLED 停用',
  ADD INDEX idx_dip_group_status (status);

ALTER TABLE dip_group_selection
  ADD COLUMN fee_override DECIMAL(12,2) NULL
    COMMENT '患者级病组费用覆盖；为空表示使用病组目录标准费用';

ALTER TABLE dip_fee_snapshot
  ADD COLUMN record_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / VOIDED',
  ADD COLUMN voided_at VARCHAR(32) NOT NULL DEFAULT '' COMMENT '作废时间',
  ADD COLUMN voided_by VARCHAR(100) NOT NULL DEFAULT '' COMMENT '作废人',
  ADD COLUMN void_reason VARCHAR(200) NOT NULL DEFAULT '' COMMENT '作废原因';

CREATE TABLE IF NOT EXISTS dip_change_log (
  id VARCHAR(64) PRIMARY KEY,
  patient_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '病组目录变更时为空',
  change_scope VARCHAR(24) NOT NULL
    COMMENT 'PATIENT_CREATE / FEE / FEE_VOID / GROUP / GROUP_FEE / CATALOG',
  target_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '病组编码或空',
  before_value VARCHAR(500) NOT NULL DEFAULT '',
  after_value VARCHAR(500) NOT NULL DEFAULT '',
  note VARCHAR(200) NOT NULL DEFAULT '',
  operator VARCHAR(100) NOT NULL DEFAULT '',
  operator_role VARCHAR(64) NOT NULL DEFAULT '',
  created_at VARCHAR(32) NOT NULL,
  INDEX idx_dip_change_patient (patient_id, created_at),
  INDEX idx_dip_change_target (target_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
