-- Align imported consumable mappings with the reviewed patient-volume workbook.
-- This changes categorisation metadata only; ledgers and stock balances remain untouched.

CREATE TEMPORARY TABLE inventory_mapping_patient_volume_classification AS
SELECT
    id,
    CASE
        WHEN CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%按需%'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%正常损耗%' THEN '按需申领'
        WHEN CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) REGEXP '[0-9]+[-~～至][0-9]+'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%大概%'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%约%'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%根据%'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%伤口%'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%筛查需%'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%手术总量%'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%患者多时%'
          OR CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) LIKE '%至少%' THEN '待核定（非固定）'
        WHEN CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) REGEXP '(每|/)?[0-9]+(天|周|月)|每天|每日|一周|两周|三天|天一换|一换' THEN '固定运行消耗'
        WHEN CONCAT_WS('', source_scenario, source_item_name, source_usage, source_note) REGEXP '(1|一)(个|支|张|片|包|套|副|份)?/(人|患者|病人)|(每人|每患者)(1|一)|单人单次'
          OR (source_sheet NOT IN ('手术室', '麻醉室', '胃肠镜') AND source_scenario REGEXP '(门诊|住院|新病号|复查).*(患者|病患)') THEN '患者单次套餐'
        ELSE '条件套餐'
    END AS rule_type
FROM inventory_mapping_entries
WHERE batch_id = 'inventory-mapping-batch-20260802'
  AND id <> 'inventory-mapping-0242';

UPDATE inventory_mapping_entries entry_row
JOIN inventory_mapping_patient_volume_classification classification ON classification.id = entry_row.id
SET
    entry_row.rule_type = classification.rule_type,
    entry_row.import_status = CASE classification.rule_type
        WHEN '患者单次套餐' THEN '待确认单次定额'
        WHEN '条件套餐' THEN '待确认业务完成量'
        WHEN '固定运行消耗' THEN '待确认周期定额'
        WHEN '待核定（非固定）' THEN '待核定患者量口径'
        ELSE '按需录入'
    END,
    entry_row.suggestion = CASE classification.rule_type
        WHEN '患者单次套餐' THEN '由科室确认单患者用量后，按已确认患者数自动测算。'
        WHEN '条件套餐' THEN '先确认对应诊疗或检查完成量，再换算耗材用量。'
        WHEN '固定运行消耗' THEN '按日、周或月的运行周期核算，不随患者数量自动扣除。'
        WHEN '待核定（非固定）' THEN '描述尚不能形成稳定患者量公式，请由科室补充触发条件或定额。'
        ELSE '按实际申领、临时损耗或突发需求记录，不纳入自动患者量扣减。'
    END,
    entry_row.cannot_publish_reason = CASE classification.rule_type
        WHEN '患者单次套餐' THEN '需由科室确认单患者用量后方可发布。'
        WHEN '条件套餐' THEN '需明确诊疗或检查完成量触发条件后方可发布。'
        WHEN '固定运行消耗' THEN '固定运行消耗不使用患者量自动扣减。'
        WHEN '待核定（非固定）' THEN '患者量绑定口径尚未明确，暂不参与自动扣减。'
        ELSE '按需申领项目不参与自动患者量扣减。'
    END,
    entry_row.raw_json = JSON_SET(COALESCE(entry_row.raw_json, JSON_OBJECT()), '$.ruleType', classification.rule_type, '$.patientVolumeRefined', TRUE);

UPDATE inventory_mapping_import_batches
SET raw_json = JSON_SET(
    COALESCE(raw_json, JSON_OBJECT()),
    '$.totalRows', 242,
    '$.patientVolumeRefined', TRUE,
    '$.ruleTypeCounts', JSON_OBJECT('患者单次套餐', 73, '条件套餐', 101, '固定运行消耗', 22, '待核定（非固定）', 24, '按需申领', 22)
)
WHERE id = 'inventory-mapping-batch-20260802';

DROP TEMPORARY TABLE inventory_mapping_patient_volume_classification;
