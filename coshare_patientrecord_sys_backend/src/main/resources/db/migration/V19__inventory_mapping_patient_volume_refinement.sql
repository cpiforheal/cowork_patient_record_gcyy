-- Keep uncertain, non-fixed usages out of patient-volume deduction until a department confirms a deterministic rule.
UPDATE inventory_mapping_entries
SET rule_type = '待核定（非固定）',
    import_status = '待核定患者量口径',
    suggestion = '定义存在区间、约数、条件差异或缺少服务维度；先按科室记录实际用量，确认单次定额后再参与患者量计算。',
    cannot_publish_reason = 'Patient-volume rule needs department confirmation.',
    raw_json = JSON_SET(
      raw_json,
      '$.ruleType', '待核定（非固定）',
      '$.importStatus', '待核定患者量口径',
      '$.suggestion', '定义存在区间、约数、条件差异或缺少服务维度；先按科室记录实际用量，确认单次定额后再参与患者量计算。',
      '$.cannotPublishReason', 'Patient-volume rule needs department confirmation.'
    )
WHERE batch_id = 'inventory-mapping-batch-20260802'
  AND (
    (source_sheet = '理疗室' AND source_row IN (4, 6, 12, 17, 18))
    OR (source_sheet = '检验科' AND source_row IN (12, 29, 35))
    OR (source_sheet = '护理部' AND source_row IN (12, 17, 18))
    OR (source_sheet = '中医科' AND source_row IN (3, 6, 8, 10))
    OR (source_sheet = '手术室' AND source_row IN (2, 3, 12, 29, 39))
    OR (source_sheet = '检查室' AND source_row = 16)
    OR (source_sheet = '中药房' AND source_row = 3)
  );

-- The original garbage-bag row retains its fixed 46-per-day baseline.  This companion row isolates only the
-- patient-peak increment so it can be measured before a patient-volume coefficient is introduced.
INSERT INTO inventory_mapping_entries (
  id, batch_id, source_sheet, source_row, department, department_id, source_scenario, source_item_name,
  source_usage, source_note, rule_type, care_type, trigger_stage, condition_text, suggested_quantity,
  suggested_unit, matched_item_id, matched_item_name, status, import_status, suggestion,
  cannot_publish_reason, draft_package_id, confirmed_at, raw_json
) VALUES (
  'inventory-mapping-0242', 'inventory-mapping-batch-20260802', '后勤保洁', 2, '后勤保洁', NULL, '后勤保洁', '垃圾袋（患者高峰增量）',
  '患者多时增加', '固定基线 46 个/天仍按固定运行消耗维护。', '待核定（非固定）', '不适用', '不适用', '高峰患者数增量待确认', 0.00,
  '个', NULL, NULL, 'pending', '待核定患者量口径', '先记录高峰期实际增量；累计后确认每患者或每服务单的增量系数。',
  'Patient-volume rule needs department confirmation.', NULL, NULL,
  JSON_OBJECT(
    'id', 'inventory-mapping-0242', 'batchId', 'inventory-mapping-batch-20260802', 'status', 'pending',
    'sourceSheet', '后勤保洁', 'sourceRow', 2, 'department', '后勤保洁', 'sourceScenario', '后勤保洁',
    'itemName', '垃圾袋（患者高峰增量）', 'sourceUsage', '患者多时增加', 'sourceNote', '固定基线 46 个/天仍按固定运行消耗维护。',
    'ruleType', '待核定（非固定）', 'careType', '不适用', 'triggerStage', '不适用', 'condition', '高峰患者数增量待确认',
    'suggestedQuantity', '', 'suggestedUnit', '个', 'importStatus', '待核定患者量口径',
    'suggestion', '先记录高峰期实际增量；累计后确认每患者或每服务单的增量系数。',
    'cannotPublishReason', 'Patient-volume rule needs department confirmation.'
  )
)
ON DUPLICATE KEY UPDATE
  rule_type = VALUES(rule_type),
  import_status = VALUES(import_status),
  suggestion = VALUES(suggestion),
  cannot_publish_reason = VALUES(cannot_publish_reason),
  source_note = VALUES(source_note),
  raw_json = VALUES(raw_json);

UPDATE inventory_mapping_import_batches
SET total_rows = 242,
    raw_json = JSON_SET(raw_json, '$.totalRows', 242, '$.patientVolumeRefined', TRUE)
WHERE id = 'inventory-mapping-batch-20260802';
