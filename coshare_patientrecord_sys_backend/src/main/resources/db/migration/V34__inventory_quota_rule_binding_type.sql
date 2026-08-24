-- 耗材绑定方式（binding_type）：区分「每人次定额 / 固定日耗 / 按需领取 / 仪器触发」。
-- 依据 2026-08 副本科室耗材使用明细的备注列回填：单位含 /天、备注为「每天用 / 一天N个 / N天一换 / 每月定量」
-- 或服务项目为「每天固定使用 / 后勤保洁」的归为固定日耗；备注「按需 / 正常损耗」归为按需领取；
-- 备注「仪器报警提示用完 / 开关机均有损耗 / 试剂盘添加」归为仪器触发；其余保持每人次定额。

ALTER TABLE inventory_quota_rules
  ADD COLUMN binding_type VARCHAR(24) NOT NULL DEFAULT 'PER_PERSON' AFTER measurement_scope;

ALTER TABLE inventory_quota_audit_log
  ADD COLUMN before_binding_type VARCHAR(24) NULL AFTER before_measurement_scope,
  ADD COLUMN after_binding_type VARCHAR(24) NULL AFTER after_measurement_scope;

-- 固定日耗：数量按天固定，不随患者人次变化（含所有定额版本的副本行）
UPDATE inventory_quota_rules SET binding_type = 'FIXED_DAILY' WHERE (department_key, material_name) IN (
  ('physiotherapy', '中单（张/天）'),
  ('physiotherapy', '黑色垃圾袋（个/天）'),
  ('physiotherapy', '医疗垃圾袋（个/天）'),
  ('physiotherapy', '口罩（个/天）'),
  ('laboratory', '医疗垃圾袋（个/天）'),
  ('laboratory', '小中单（个/天）'),
  ('laboratory', '大中单（个/天）'),
  ('laboratory', '医用抗菌洗手液'),
  ('laboratory', '84消毒液'),
  ('laboratory', '一次性消毒凝胶'),
  ('nursing', '20ml注射器（个/天）'),
  ('nursing', '84消毒'),
  ('nursing', 'A4纸（张/天）'),
  ('nursing', '口罩（个/天）'),
  ('nursing', '黑色垃圾袋（个/天）'),
  ('nursing', 'PVC手套（双/天）'),
  ('nursing', '薄膜手套（双/天）'),
  ('operating', '大中单（个/天）'),
  ('operating', '帽子（个/天）'),
  ('operating', '黄色垃圾袋（个/天）'),
  ('operating', '利器盒（个/天）'),
  ('operating', '口罩（个/天）'),
  ('operating', '黑色垃圾袋（个/天）'),
  ('anesthesia', '84消毒液（ml/天）'),
  ('anesthesia', '大中单（个/天）'),
  ('anesthesia', '手术衣（个/天）'),
  ('anesthesia', '20毫升注射器（个/天）'),
  ('anesthesia', '酶液（ml/天）'),
  ('anesthesia', '戊二醛（瓶/天）'),
  ('anesthesia', '口罩（个/天）'),
  ('anesthesia', '帽子（个/天）'),
  ('endoscopy', '84消毒液（ml/天）'),
  ('endoscopy', '大中单（个/天）'),
  ('endoscopy', '手术衣（个/天）'),
  ('endoscopy', '20毫升注射器（个/天）'),
  ('endoscopy', '酶液（ml/天）'),
  ('endoscopy', '戊二醛（瓶/天）'),
  ('endoscopy', '口罩（个/天）'),
  ('endoscopy', '帽子（个/天）'),
  ('inspection', '医疗垃圾袋（个）'),
  ('inspection', '黑色垃圾袋（个）'),
  ('inspection', '84消毒液（ml）'),
  ('inspection', '洗手液（ml）'),
  ('inspection', '手消（ml）'),
  ('inspection', '口罩（个/天）'),
  ('inspection', '标签贴（个/天）'),
  ('inspection', '中单（个/天）'),
  ('logistics', '垃圾袋（个/天）'),
  ('logistics', '84消毒液（瓶/天）'),
  ('logistics', '洁厕灵（瓶/天）'),
  ('logistics', '口罩（个/天）'),
  ('tcm-pharmacy', 'Pvc手套（盒/天）')
);

-- 按需领取：无固定规律，按实际领取量填报
UPDATE inventory_quota_rules SET binding_type = 'ON_DEMAND' WHERE (department_key, material_name) IN (
  ('physiotherapy', '橡胶检查手套（双）'),
  ('laboratory', '利器盒'),
  ('laboratory', '卫生纸'),
  ('laboratory', 'C14试剂'),
  ('laboratory', 'CRP试剂'),
  ('laboratory', '糖化血红蛋白试剂'),
  ('nursing', '20ml注射器'),
  ('nursing', '利器盒'),
  ('nursing', '固体胶'),
  ('tcm', '签字笔芯'),
  ('operating', '10毫升过氧化氢'),
  ('operating', '20厘米橡胶管'),
  ('inspection', '处方签'),
  ('logistics', '拖把、扫把等'),
  ('western-pharmacy', '打印纸'),
  ('cashier', '针式打印纸'),
  ('tcm-pharmacy', '针式打印纸')
);

-- 仪器触发：由检验/清洗设备的开关机与报警损耗驱动，与人次无关
UPDATE inventory_quota_rules SET binding_type = 'EQUIPMENT' WHERE (department_key, material_name) IN (
  ('laboratory', '生化DC-80清洗液'),
  ('laboratory', '血常规稀释液（ml）'),
  ('laboratory', '溶血剂（ml）'),
  ('laboratory', '探头清洁液（ml）'),
  ('laboratory', '生化试剂14项'),
  ('laboratory', '电解质试剂')
);
