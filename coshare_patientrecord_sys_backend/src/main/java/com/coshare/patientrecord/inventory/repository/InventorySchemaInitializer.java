package com.coshare.patientrecord.inventory.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
class InventorySchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    InventorySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void initializeSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_items (
              id VARCHAR(64) PRIMARY KEY,
              name VARCHAR(120) NOT NULL,
              category VARCHAR(80),
              spec VARCHAR(120),
              unit VARCHAR(32),
              location VARCHAR(120),
              low_stock_threshold DECIMAL(12,2) DEFAULT 0,
              is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
              batch_required BOOLEAN NOT NULL DEFAULT FALSE,
              expiry_required BOOLEAN NOT NULL DEFAULT FALSE,
              enabled BOOLEAN NOT NULL DEFAULT TRUE,
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_inventory_items_name (name),
              INDEX idx_inventory_items_category (category)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_batches (
              id VARCHAR(64) PRIMARY KEY,
              item_id VARCHAR(64) NOT NULL,
              batch_no VARCHAR(120),
              expiry_date VARCHAR(32),
              quantity DECIMAL(12,2) NOT NULL DEFAULT 0,
              location VARCHAR(120),
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_inventory_batches_item (item_id),
              INDEX idx_inventory_batches_expiry (expiry_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_requests (
              id VARCHAR(64) PRIMARY KEY,
              department VARCHAR(120) NOT NULL,
              applicant VARCHAR(120),
              owner VARCHAR(120),
              reason VARCHAR(500),
              expected_use_week VARCHAR(32),
              status VARCHAR(32),
              created_at VARCHAR(32),
              approved_at VARCHAR(32),
              issued_at VARCHAR(32),
              received_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_requests_department (department),
              INDEX idx_inventory_requests_status (status),
              INDEX idx_inventory_requests_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_request_lines (
              id VARCHAR(64) PRIMARY KEY,
              request_id VARCHAR(64) NOT NULL,
              item_id VARCHAR(64) NOT NULL,
              requested_quantity DECIMAL(12,2) NOT NULL DEFAULT 0,
              issued_quantity DECIMAL(12,2) NOT NULL DEFAULT 0,
              line_status VARCHAR(32),
              raw_json JSON NOT NULL,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_inventory_request_lines_request (request_id),
              INDEX idx_inventory_request_lines_item (item_id),
              INDEX idx_inventory_request_lines_status (line_status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_weekly_consumption (
              id VARCHAR(64) PRIMARY KEY,
              week_no VARCHAR(32),
              department VARCHAR(120),
              item_id VARCHAR(64),
              consumed_quantity DECIMAL(12,2) DEFAULT 0,
              remaining_quantity DECIMAL(12,2) DEFAULT 0,
              next_week_quantity DECIMAL(12,2) DEFAULT 0,
              owner VARCHAR(120),
              abnormal_reason VARCHAR(500),
              confirmed_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_weekly_department (department),
              INDEX idx_inventory_weekly_item (item_id),
              INDEX idx_inventory_weekly_week (week_no),
              UNIQUE KEY uk_inventory_weekly_scope (week_no, department, item_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        ensureWeeklyConsumptionUniqueKey();
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_counts (
              id VARCHAR(64) PRIMARY KEY,
              item_id VARCHAR(64),
              batch_id VARCHAR(64),
              book_quantity DECIMAL(12,2) DEFAULT 0,
              actual_quantity DECIMAL(12,2) DEFAULT 0,
              difference_quantity DECIMAL(12,2) DEFAULT 0,
              operator_name VARCHAR(120),
              reason VARCHAR(500),
              counted_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_counts_item (item_id),
              INDEX idx_inventory_counts_counted (counted_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_movements (
              id VARCHAR(64) PRIMARY KEY,
              item_id VARCHAR(64) NOT NULL,
              batch_id VARCHAR(64),
              movement_type VARCHAR(40) NOT NULL,
              quantity DECIMAL(12,2) NOT NULL,
              department VARCHAR(120),
              operator_name VARCHAR(120),
              reason VARCHAR(500),
              related_id VARCHAR(64),
              created_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_movements_item (item_id),
              INDEX idx_inventory_movements_type (movement_type),
              INDEX idx_inventory_movements_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS inventory_audit_logs (
              id VARCHAR(64) PRIMARY KEY,
              operator_name VARCHAR(120),
              action VARCHAR(120),
              target_type VARCHAR(80),
              target_label VARCHAR(200),
              detail VARCHAR(800),
              created_at VARCHAR(32),
              raw_json JSON NOT NULL,
              INDEX idx_inventory_audit_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }

    private void ensureWeeklyConsumptionUniqueKey() {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(1)
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'inventory_weekly_consumption'
              AND index_name = 'uk_inventory_weekly_scope'
            """,
            Integer.class
        );
        if (count != null && count > 0) return;

        jdbcTemplate.execute("""
            DELETE older
            FROM inventory_weekly_consumption older
            JOIN inventory_weekly_consumption newer
              ON older.week_no <=> newer.week_no
             AND older.department <=> newer.department
             AND older.item_id <=> newer.item_id
             AND older.id > newer.id
            """);
        jdbcTemplate.execute("""
            ALTER TABLE inventory_weekly_consumption
            ADD UNIQUE KEY uk_inventory_weekly_scope (week_no, department, item_id)
            """);
    }
}
