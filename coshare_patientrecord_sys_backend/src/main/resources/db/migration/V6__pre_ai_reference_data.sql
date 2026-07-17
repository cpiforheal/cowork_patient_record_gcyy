-- Move the former startup data repair into a repeatable, versioned migration.
-- Existing case ids are preserved; only encounters without a case id are grouped.

INSERT IGNORE INTO pre_ai_patient_cases (
    id, source_patient_id, patient_json, created_at, updated_at
)
SELECT
    e.patient_case_id,
    MAX(COALESCE(e.source_patient_id, '')),
    JSON_EXTRACT(MAX(CAST(e.patient_json AS CHAR)), '$'),
    MIN(e.created_at),
    MAX(e.updated_at)
FROM pre_ai_encounters e
WHERE COALESCE(e.patient_case_id, '') <> ''
GROUP BY e.patient_case_id;

CREATE TEMPORARY TABLE pre_ai_missing_case_groups AS
SELECT
    CASE
        WHEN COALESCE(source_patient_id, '') = '' THEN CONCAT('encounter:', id)
        ELSE CONCAT('source:', source_patient_id)
    END AS group_key,
    CONCAT('pcase-', REPLACE(UUID(), '-', '')) AS patient_case_id
FROM pre_ai_encounters
WHERE COALESCE(patient_case_id, '') = ''
GROUP BY group_key;

INSERT IGNORE INTO pre_ai_patient_cases (
    id, source_patient_id, patient_json, created_at, updated_at
)
SELECT
    g.patient_case_id,
    MAX(COALESCE(e.source_patient_id, '')),
    JSON_EXTRACT(MAX(CAST(e.patient_json AS CHAR)), '$'),
    MIN(e.created_at),
    MAX(e.updated_at)
FROM pre_ai_missing_case_groups g
JOIN pre_ai_encounters e
  ON g.group_key = CASE
      WHEN COALESCE(e.source_patient_id, '') = '' THEN CONCAT('encounter:', e.id)
      ELSE CONCAT('source:', e.source_patient_id)
  END
GROUP BY g.patient_case_id;

UPDATE pre_ai_encounters e
JOIN pre_ai_missing_case_groups g
  ON g.group_key = CASE
      WHEN COALESCE(e.source_patient_id, '') = '' THEN CONCAT('encounter:', e.id)
      ELSE CONCAT('source:', e.source_patient_id)
  END
SET e.patient_case_id = g.patient_case_id
WHERE COALESCE(e.patient_case_id, '') = '';

DROP TEMPORARY TABLE pre_ai_missing_case_groups;

UPDATE pre_ai_encounters e
JOIN (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY patient_case_id
            ORDER BY created_at, id
        ) AS new_visit_no
    FROM pre_ai_encounters
    WHERE COALESCE(patient_case_id, '') <> ''
) numbered ON numbered.id = e.id
SET e.visit_no = numbered.new_visit_no;

INSERT INTO pre_ai_auxiliary_tasks (
    id, encounter_id, task_type, title, owner_role,
    required_before_export, status, data_json, version,
    completed_at, updated_at, created_at, created_by
)
SELECT
    CONCAT('aux-lab-', REPLACE(UUID(), '-', '')),
    e.id,
    'LAB',
    '化验室检验报告',
    'lab',
    TRUE,
    'DRAFT',
    JSON_OBJECT(),
    0,
    '',
    DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s'),
    DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:%s'),
    'system'
FROM pre_ai_encounters e
WHERE NOT EXISTS (
    SELECT 1
    FROM pre_ai_auxiliary_tasks t
    WHERE t.encounter_id = e.id AND t.task_type = 'LAB'
);
