CREATE TABLE IF NOT EXISTS clinic_ai_document_tasks (
    id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    request_json JSON NOT NULL,
    result_json JSON NULL,
    error_message VARCHAR(1000) NULL,
    attempt INT NOT NULL DEFAULT 1,
    created_by VARCHAR(100) NOT NULL,
    created_by_role VARCHAR(64) NOT NULL,
    created_at VARCHAR(32) NOT NULL,
    started_at VARCHAR(32) NULL,
    finished_at VARCHAR(32) NULL,
    updated_at VARCHAR(32) NOT NULL,
    INDEX idx_ai_document_task_owner (created_by, created_at),
    INDEX idx_ai_document_task_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
