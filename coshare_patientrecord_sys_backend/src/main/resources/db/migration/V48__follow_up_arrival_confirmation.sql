-- 随访依从性闭环（A2）：回院确认
-- 背景：原先只记录"是否联系过"（审计事件 followup.recall.contact），
-- 无法回答"患者最终是否回院"，导致完成率统计的是联系动作而非依从性。
-- 本迁移为每个复诊节点增加可查询的回院事实字段，供回院率统计与召回清单使用。
ALTER TABLE pre_ai_follow_up_visits
  ADD COLUMN arrived_at VARCHAR(32) NULL AFTER next_review_date,
  ADD COLUMN arrived_by VARCHAR(100) NULL AFTER arrived_at,
  ADD COLUMN arrived_encounter_id VARCHAR(64) NULL AFTER arrived_by,
  ADD INDEX idx_follow_up_arrived (arrived_at);
