-- 中药房固定耗材按“个”计数：方便袋（带字）定额按“把”下发，1 把 = 100 个。
-- 填报时直接填个数，系统自动折算成把，不再要求科室手工换算。
UPDATE inventory_items
SET count_unit = '个', count_per_unit = 100
WHERE name = '方便袋（带字）' AND unit = '把'
  AND (count_unit IS NULL OR count_per_unit IS NULL);
