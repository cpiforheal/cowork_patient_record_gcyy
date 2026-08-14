CREATE TABLE inventory_department_allocation_plans (
    id VARCHAR(80) PRIMARY KEY,
    department_key VARCHAR(64) NOT NULL,
    department_name VARCHAR(120) NOT NULL,
    business_month DATE NOT NULL,
    revision INT NOT NULL,
    operator_name VARCHAR(120) NOT NULL,
    operator_username VARCHAR(120) NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory_department_allocation_month (department_key, business_month)
);

CREATE TABLE inventory_department_allocation_plan_lines (
    id VARCHAR(80) PRIMARY KEY,
    plan_id VARCHAR(80) NOT NULL,
    material_name VARCHAR(200) NOT NULL,
    unit VARCHAR(64) NOT NULL,
    allocated_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
    source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    count_reference VARCHAR(200) NOT NULL DEFAULT '',
    manual_adjustment DECIMAL(18,6) NOT NULL DEFAULT 0,
    warning_threshold DECIMAL(18,6) NULL,
    UNIQUE KEY uk_inventory_department_allocation_line (plan_id, material_name, unit),
    CONSTRAINT fk_inventory_department_allocation_plan_line FOREIGN KEY (plan_id) REFERENCES inventory_department_allocation_plans(id) ON DELETE CASCADE
);
