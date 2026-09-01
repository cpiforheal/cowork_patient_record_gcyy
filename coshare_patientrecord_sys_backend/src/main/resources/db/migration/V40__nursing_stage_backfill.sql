-- 护理部（NURSING）为新增阶段（位于接诊室之后、仅住院患者必填）。
-- 存量就诊统一补建 SKIPPED 行：避免历史就诊被"护理部未完成"卡住复核与导出；
-- 新建就诊由应用按 STAGE_ORDER 自动建行。幂等：已存在 NURSING 行的就诊不重复插入。
INSERT INTO pre_ai_stage_submissions (
  encounter_id, stage_code, status, version, data_json, returned_reason,
  submitted_by, submitted_by_role, completed_at, updated_at
)
SELECT
  e.id, 'NURSING', 'SKIPPED', 0, JSON_OBJECT(), NULL,
  'migration', 'system', NULL, DATE_FORMAT(NOW(3), '%Y-%m-%d %H:%i:%s')
FROM pre_ai_encounters e
WHERE NOT EXISTS (
  SELECT 1 FROM pre_ai_stage_submissions s
  WHERE s.encounter_id = e.id AND s.stage_code = 'NURSING'
);
