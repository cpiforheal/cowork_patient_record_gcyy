ALTER TABLE pre_ai_exports
    ADD COLUMN template_version VARCHAR(64) NULL AFTER status;
