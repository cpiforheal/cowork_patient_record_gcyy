-- 精修轮（referenceAssetId 提交）复用系统已过安检的既往产物，不产生新的消毒报告；
-- 报告列需要允许为空，否则第二轮精修任务插入必然违反外键约束。
ALTER TABLE clinic_medical_record_generation_tasks
  MODIFY COLUMN sanitization_report_id VARCHAR(64) NULL;
