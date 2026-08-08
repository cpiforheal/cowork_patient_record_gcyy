-- A monotonic sequence makes same-second responsibility events deterministic.
-- Existing rows receive a generated sequence only; no historical content is changed.
ALTER TABLE pre_ai_audit_logs
  ADD COLUMN timeline_sequence BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  ADD UNIQUE KEY uq_pre_ai_audit_timeline_sequence (timeline_sequence),
  ADD INDEX idx_pre_ai_audit_encounter_sequence (encounter_id, timeline_sequence);
