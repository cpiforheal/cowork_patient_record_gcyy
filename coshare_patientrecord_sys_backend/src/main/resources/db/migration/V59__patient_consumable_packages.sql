-- Reusable patient consumable packages saved from the workbench selection.

CREATE TABLE inventory_patient_consumable_packages (
  id VARCHAR(64) PRIMARY KEY,
  department_key VARCHAR(64) NOT NULL,
  department_name VARCHAR(120) NOT NULL,
  name VARCHAR(120) NOT NULL,
  created_by VARCHAR(120) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inventory_consumable_package (department_key, name),
  INDEX idx_inventory_consumable_package_department (department_key, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_patient_consumable_package_lines (
  id VARCHAR(64) PRIMARY KEY,
  package_id VARCHAR(64) NOT NULL,
  line_no INT NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  item_name_snapshot VARCHAR(200) NOT NULL,
  unit_snapshot VARCHAR(64) NOT NULL,
  quantity DECIMAL(14,2) NOT NULL,
  source VARCHAR(24) NOT NULL DEFAULT 'PRESET',
  exception_reason VARCHAR(500) NULL,
  raw_json JSON NOT NULL,
  UNIQUE KEY uk_inventory_consumable_package_line (package_id, line_no),
  INDEX idx_inventory_consumable_package_line_item (item_id),
  CONSTRAINT fk_inventory_consumable_package_line_package FOREIGN KEY (package_id) REFERENCES inventory_patient_consumable_packages(id),
  CONSTRAINT fk_inventory_consumable_package_line_item FOREIGN KEY (item_id) REFERENCES inventory_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
