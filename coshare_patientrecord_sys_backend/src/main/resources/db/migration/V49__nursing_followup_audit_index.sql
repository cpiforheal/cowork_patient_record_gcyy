-- 护理随访留痕监控台：为按动作跨就诊检索补索引。
-- 既有索引是 (encounter_id, created_at)，只适合单就诊时间轴；
-- 监控台按 action 过滤并跨全部就诊倒序分页，需要独立索引。
-- 纯新增，不改既有结构。
ALTER TABLE pre_ai_audit_logs
  ADD INDEX idx_pre_ai_audit_action_time (action, created_at);
