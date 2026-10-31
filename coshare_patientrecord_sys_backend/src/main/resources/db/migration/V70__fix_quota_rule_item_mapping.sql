UPDATE inventory_quota_rules r
JOIN inventory_quota_versions v ON v.id = r.version_id AND v.status = 'ACTIVE'
JOIN (
  SELECT TRIM(SUBSTRING_INDEX(REPLACE(name, '(', '（'), '（', 1)) AS base_name,
         MIN(id) AS item_id,
         COUNT(*) AS item_count
  FROM inventory_items
  WHERE enabled = 1
  GROUP BY base_name
) matched ON matched.base_name = TRIM(SUBSTRING_INDEX(REPLACE(r.material_name, '(', '（'), '（', 1))
SET r.item_id = matched.item_id
WHERE r.enabled = 1
  AND matched.item_count = 1
  AND (r.item_id IS NULL OR r.item_id <> matched.item_id);
