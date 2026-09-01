-- 护理部开放口径修正：接诊室判定住院的在途就诊，恢复被 V40 批量跳过且未被人工处理过的护理部行。
-- 仅处理：接诊室已完成、判定 INPATIENT、就诊仍在进行中、护理部行为 V40 回填的初始 SKIPPED（version=0）。
UPDATE pre_ai_stage_submissions s
JOIN pre_ai_stage_submissions r
  ON r.encounter_id = s.encounter_id AND r.stage_code = 'RECEPTION'
JOIN pre_ai_encounters e
  ON e.id = s.encounter_id
SET s.status = 'DRAFT',
    s.updated_at = DATE_FORMAT(NOW(3), '%Y-%m-%d %H:%i:%s')
WHERE s.stage_code = 'NURSING'
  AND s.status = 'SKIPPED'
  AND s.version = 0
  AND r.status = 'COMPLETED'
  AND JSON_UNQUOTE(JSON_EXTRACT(r.data_json, '$.dispositionSuggestion')) = 'INPATIENT'
  AND e.status = 'IN_PROGRESS';
