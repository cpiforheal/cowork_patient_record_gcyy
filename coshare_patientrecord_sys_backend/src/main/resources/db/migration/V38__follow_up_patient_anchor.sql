-- 复诊记录锚点从"就诊"改为"患者主档案"：复诊患者不经前台登记，检查室直接按患者创建
ALTER TABLE pre_ai_follow_up_visits
  ADD COLUMN patient_case_id VARCHAR(64) NULL AFTER encounter_id,
  ADD INDEX idx_follow_up_patient (patient_case_id, seq);
