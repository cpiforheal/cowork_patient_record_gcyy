CREATE TABLE inventory_inbound_orders (
  id VARCHAR(64) PRIMARY KEY,
  order_no VARCHAR(40) NOT NULL,
  supplier VARCHAR(160) NULL,
  purchase_date DATE NOT NULL,
  supplier_document_no VARCHAR(120) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  note VARCHAR(500) NULL,
  created_by VARCHAR(120) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reversed_by VARCHAR(120) NULL,
  reverse_reason VARCHAR(500) NULL,
  reversed_at DATETIME(3) NULL,
  UNIQUE KEY uk_inventory_inbound_orders_no (order_no),
  KEY idx_inventory_inbound_orders_date (purchase_date, status),
  KEY idx_inventory_inbound_orders_supplier (supplier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_inbound_lines (
  id VARCHAR(64) PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL,
  item_id VARCHAR(64) NOT NULL,
  batch_id VARCHAR(64) NOT NULL,
  batch_no VARCHAR(120) NULL,
  expiry_date VARCHAR(32) NULL,
  issue_quantity DECIMAL(14,2) NOT NULL,
  conversion_factor DECIMAL(14,4) NOT NULL DEFAULT 1,
  quantity DECIMAL(14,2) NOT NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_inventory_inbound_lines_order (order_id),
  KEY idx_inventory_inbound_lines_item (item_id, created_at),
  KEY idx_inventory_inbound_lines_batch (batch_id),
  CONSTRAINT fk_inventory_inbound_lines_order FOREIGN KEY (order_id) REFERENCES inventory_inbound_orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
