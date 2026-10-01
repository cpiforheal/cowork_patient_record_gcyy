-- 复查预约登记表：检查室人员手工登记"哪天谁来复查"，交接班时核对实到情况。
-- 状态：PLANNED 待到 / ARRIVED 已到 / ABSENT 未到 / RESCHEDULED 已改期 / CANCELLED 已撤销（软删）
CREATE TABLE IF NOT EXISTS clinic_recheck_schedule (
  id VARCHAR(64) PRIMARY KEY,
  plan_date VARCHAR(10) NOT NULL,
  patient_name VARCHAR(50) NOT NULL,
  phone VARCHAR(30) NOT NULL DEFAULT '',
  note VARCHAR(200) NOT NULL DEFAULT '',
  status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
  status_note VARCHAR(200) NOT NULL DEFAULT '',
  source_id VARCHAR(64),
  rescheduled_to VARCHAR(10),
  created_by VARCHAR(100),
  created_at VARCHAR(32),
  updated_by VARCHAR(100),
  updated_at VARCHAR(32),
  INDEX idx_recheck_plan_date (plan_date),
  INDEX idx_recheck_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
