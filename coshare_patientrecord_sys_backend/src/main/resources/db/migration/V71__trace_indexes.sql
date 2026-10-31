ALTER TABLE inventory_ledger_movements
  ADD INDEX idx_inventory_ledger_batch_date (batch_id, occurred_at);
