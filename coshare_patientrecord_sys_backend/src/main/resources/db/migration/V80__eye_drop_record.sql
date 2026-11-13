CREATE TABLE IF NOT EXISTS clinic_eye_drop_plan (
  id VARCHAR(64) PRIMARY KEY,
  drug_code VARCHAR(32) NOT NULL,
  drug_name VARCHAR(80) NOT NULL,
  slot_code VARCHAR(16) NOT NULL,
  slot_label VARCHAR(16) NOT NULL,
  planned_time TIME NOT NULL,
  window_minutes INT NOT NULL DEFAULT 120,
  sort_no INT NOT NULL,
  active TINYINT NOT NULL DEFAULT 1,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_eye_drop_plan (drug_code, slot_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_eye_drop_log (
  id VARCHAR(64) PRIMARY KEY,
  plan_id VARCHAR(64) NOT NULL,
  take_date DATE NOT NULL,
  drug_code VARCHAR(32) NOT NULL,
  slot_code VARCHAR(16) NOT NULL,
  eye_side VARCHAR(8) NOT NULL DEFAULT 'BOTH',
  planned_time TIME NOT NULL,
  taken_at DATETIME(3) NOT NULL,
  late TINYINT NOT NULL DEFAULT 0,
  minutes_late INT NOT NULL DEFAULT 0,
  note VARCHAR(500) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'TAKEN',
  request_id VARCHAR(64) NULL,
  voided_at DATETIME(3) NULL,
  voided_by VARCHAR(100) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_eye_drop_log_slot (take_date, plan_id),
  UNIQUE KEY uk_eye_drop_log_request (request_id),
  KEY idx_eye_drop_log_date (take_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_eye_drop_measurement (
  id VARCHAR(64) PRIMARY KEY,
  measured_at DATETIME(3) NOT NULL,
  iop_left DECIMAL(4,1) NULL,
  iop_right DECIMAL(4,1) NULL,
  symptoms_json JSON NULL,
  symptom_note VARCHAR(500) NULL,
  dose_log_id VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_eye_drop_measure_date (measured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO clinic_eye_drop_plan
  (id, drug_code, drug_name, slot_code, slot_label, planned_time, window_minutes, sort_no, active)
VALUES
  ('edp-bri-am', 'BRIMONIDINE', '酒石酸溴莫尼定', 'AM', '早', '07:30:00', 120, 1, 1),
  ('edp-bri-noon', 'BRIMONIDINE', '酒石酸溴莫尼定', 'NOON', '中', '12:30:00', 120, 2, 1),
  ('edp-bzt-am', 'BRINZOLAMIDE_TIMOLOL', '布林佐胺噻吗洛尔', 'AM', '早', '07:30:00', 120, 3, 1),
  ('edp-bzt-noon', 'BRINZOLAMIDE_TIMOLOL', '布林佐胺噻吗洛尔', 'NOON', '中', '12:30:00', 120, 4, 1),
  ('edp-bzt-pm', 'BRINZOLAMIDE_TIMOLOL', '布林佐胺噻吗洛尔', 'PM', '晚', '18:30:00', 120, 5, 1),
  ('edp-bim-bedtime', 'BIMATOPROST', '贝美前列素', 'BEDTIME', '睡前', '22:30:00', 120, 6, 1)
ON DUPLICATE KEY UPDATE drug_name = VALUES(drug_name), slot_label = VALUES(slot_label);
