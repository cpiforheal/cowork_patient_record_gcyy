ALTER TABLE pre_ai_lab_reports
  ADD UNIQUE KEY uq_pre_ai_lab_report_version (encounter_id, template_id, report_date, version);
