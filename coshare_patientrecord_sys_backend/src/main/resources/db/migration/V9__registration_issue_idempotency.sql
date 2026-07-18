ALTER TABLE pre_ai_encounters
    ADD COLUMN registration_request_id VARCHAR(64) NULL AFTER reviewed_by_role,
    ADD UNIQUE KEY uk_pre_ai_encounters_registration_request (registration_request_id);
