INSERT INTO clinic_queue_print_templates (template_code, config_json, updated_by, updated_at)
VALUES (
  'CLINIC_QUEUE_TICKET',
  JSON_OBJECT(
    'institutionName', '门诊部',
    'title', '排队凭证',
    'paperWidth', 58,
    'numberFontSize', 42,
    'compact', TRUE,
    'showMaskedName', TRUE,
    'showVisitType', TRUE,
    'showFirstStage', TRUE,
    'showIssuedAt', TRUE,
    'showNotice', TRUE,
    'notice', '请留意大屏及语音叫号',
    'secondaryNotice', '检查完成后沿用本号码'
  ),
  'flyway',
  NOW()
)
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

INSERT INTO clinic_queue_rooms (
  room_code, room_name, stage_code, status, pause_reason, follow_up_streak, version, updated_by, updated_at
) VALUES
  ('INSPECTION_ROOM', '检查室', 'INSPECTION', 'ACTIVE', '', 0, 0, 'flyway', NOW()),
  ('RECEPTION_ROOM', '接诊室', 'RECEPTION', 'ACTIVE', '', 0, 0, 'flyway', NOW())
ON DUPLICATE KEY UPDATE room_code = VALUES(room_code);
