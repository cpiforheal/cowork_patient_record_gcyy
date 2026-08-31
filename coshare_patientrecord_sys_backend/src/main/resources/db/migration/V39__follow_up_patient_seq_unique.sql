-- 复诊序列改为按患者累计（锚点 patientCaseId），唯一键同步重建；
-- 旧 (encounter_id, seq) 唯一键会阻止同一就诊下的患者级递增序号。
ALTER TABLE pre_ai_follow_up_visits DROP INDEX uq_follow_up_seq;
ALTER TABLE pre_ai_follow_up_visits ADD UNIQUE KEY uq_follow_up_patient_seq (patient_case_id, seq);
